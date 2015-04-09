package ch.ethz.inf.da.mammoth

import ch.ethz.inf.da.mammoth.document.{TokenDocument, StringDocument}
import ch.ethz.inf.da.mammoth.warc.splitWarcFile
import ch.ethz.inf.da.mammoth.preprocess.{htmlToText, tokenize, lowercase, removeStopwords, removeLessThan, removeGreaterThan}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.feature.DictionaryTF
import org.apache.spark.mllib.feature.IDF
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.clustering.LDA

/**
 * Preprocesses the raw HTML data
 */
object Main {

  /**
   * Main entry point of the application
   * @param args The command-line arguments
   */
  def main(args: Array[String]) {

    // Set up spark context
    val sc = createSparkContext()

    // Get an RDD of all cleaned preprocessed documents
    val documents = getDocuments(sc, "hdfs://127.0.0.1:9000/cw-data/*")

    // Compute a dictionary with a maximum size. It takes the n most frequent terms
    val dictionary = new DictionaryTF(10000000)
    dictionary.fit(documents)

    // Compute document vectors and zip them with identifiers that are ints
    val tfVectors = dictionary.transform(documents)
    val tfidfVectors = (new IDF()).fit(tfVectors).transform(tfVectors)
    val ldaInput = documents.map(doc => doc.id.replaceAll("""[^0-9]+""", "").toLong).zip(tfidfVectors).cache()

    // Compute LDA with 10 topics and a maximum of 10 iterations
    val numTopics = 100
    val numIterations = 10
    val lda = new LDA().setK(numTopics).setMaxIterations(numIterations)
    val ldaModel = lda.run(ldaInput)

    // Print the computed model and its statistics
    val avgLogLikelihood = ldaModel.logLikelihood / documents.count()
    val topicIndices = ldaModel.describeTopics(maxTermsPerTopic = 15)
    val inverseDictionary = dictionary.mapping.map(_.swap).toMap
    topicIndices.foreach { case (terms, termWeights) =>
      println("TOPIC:")
      terms.zip(termWeights).foreach { case (term, weight) =>
        println(s"${inverseDictionary(term.toInt)}\t$weight")
      }
      println()
    }
    println(s"Avg Log-Likelihood: $avgLogLikelihood")


  }

  /**
   * Gets cleaned, preprocessed and tokenized documents from given input
   *
   * @param sc The spark context
   * @param input The input as an URL (e.g. hdfs://...)
   * @return An RDD of the cleaned documents
   */
  def getDocuments(sc:SparkContext, input:String): RDD[TokenDocument] = {
    
    // Distribute all WARC files
    val files = sc.wholeTextFiles(input)

    // Flat map each WARC file to multiple HTML documents
    val htmlDocuments = files.flatMap(x ⇒ splitWarcFile(x._2))

    // Map each HTML document to its plain text equivalent
    val plainTextDocuments = htmlDocuments.map(doc ⇒ new StringDocument(doc.id, htmlToText(doc.contents)))

    // Tokenize the plain text documents
    val tokenizedDocuments = plainTextDocuments.map(doc ⇒ new TokenDocument(doc.id, tokenize(doc.contents)))

    // Perform text preprocessing on the tokens
    // Convert to lowercase, remove stopwords, remove very small and very large words:
    def textProcess(tokens:Iterable[String]): Iterable[String] =
      removeGreaterThan(removeLessThan(removeStopwords(lowercase(tokens)), 2), 30)

    tokenizedDocuments.map(doc ⇒ new TokenDocument(doc.id, textProcess(doc.tokens)))

  }

  /**
   * Creates a spark context
   * @return The spark context
   */
  def createSparkContext(): SparkContext = {
    val sparkConf = new SparkConf().setAppName("Mammoth")
    sparkConf.set("local", "true")
    sparkConf.setMaster("local[*]")
    new SparkContext(sparkConf)
  }

}

