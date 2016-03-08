package ch.ethz.inf.da.mammoth

import java.io.File

import akka.util.Timeout
import breeze.linalg.SparseVector
import ch.ethz.inf.da.mammoth.document.TokenDocument
import ch.ethz.inf.da.mammoth.io.{CluewebReader, DictionaryIO}
import glintlda.{LDAConfig, Solver}
import ch.ethz.inf.da.mammoth.util.fileExists
import com.typesafe.config.ConfigFactory
import glint.Client
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.mllib.feature.{Dictionary, DictionaryTF}
import org.apache.spark.mllib.clustering.{LocalLDAModel, DistributedLDAModel, LDA}
import org.apache.spark.mllib.linalg
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkConf, SparkContext}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
  * Defines the command line options
  */
case class Config(
                   algorithm: String = "mh",
                   blockSize: Int = 1000,
                   checkpointSave: String = "",
                   checkpointRead: String = "",
                   glintConfig: File = new File(""),
                   datasetLocation: String = "",
                   dictionaryLocation: String = "",
                   finalModel: String = "",
                   iterations: Int = 100,
                   mhSteps: Int = 2,
                   partitions: Int = 336,
                   rddLocation: String = "",
                   seed: Int = 42,
                   var topics: Int = 30,
                   var vocabularySize: Int = 60000,
                   α: Double = 0.5,
                   β: Double = 0.01,
                   τ: Int = 1
                  )

/**
  * Main application
  */
object Main {

  /**
    * Entry point of the application
    * This parses the command line options and executes the run method
    *
    * @param args The command line arguments
    */
  def main(args: Array[String]) {

    val default = new Config()
    val parser = new scopt.OptionParser[Config]("") {
      head("Mammoth", "0.1")

      opt[String]('a', "algorithm") action {
        (x, c) => c.copy(algorithm = x)
      } validate {
        case x: String =>
          if (Set("spark", "mh", "naive").contains(x)) {
            success
          } else {
            failure("algorithm must be either spark, mh or naive")
          }
      } text s"The algorithm to use (either spark, mh or naive)"

      opt[Int]('b', "blocksize") action {
        (x, c) => c.copy(blockSize = x)
      } text s"The size of a block of parameters to process at a time (default: ${default.blockSize})"

      opt[File]('c', "glintconfig") action {
        (x, c) => c.copy(glintConfig = x)
      } text s"The glint configuration file"

      opt[String]('d', "dataset") required() action {
        (x, c) => c.copy(datasetLocation = x)
      } text "The directory where the dataset is located"

      opt[String]("dictionary") action {
        (x, c) => c.copy(dictionaryLocation = x)
      } text s"The dictionary file (if it does not exist, a dictionary will be created there)"

      opt[String]('f', "final") action {
        (x, c) => c.copy(finalModel = x)
      } text s"The file where the final topic model will be stored"

      opt[Int]('i', "iterations") action {
        (x, c) => c.copy(iterations = x)
      } text s"The number of iterations (default: ${default.iterations})"

      opt[Int]('m', "metropolishastings") action {
        (x, c) => c.copy(mhSteps = x)
      }  validate {
        case x => if (x > 0) success else failure("Number of metropolis-hastings steps must be larger than 0")
      } text s"The number of metropolis-hastings steps (default: ${default.mhSteps})"

      opt[Int]('p', "partitions") action {
        (x, c) => c.copy(partitions = x)
      } text s"The number of partitions to split the data in (default: ${default.partitions})"

      opt[String]('r', "rdd") action {
        (x, c) => c.copy(rddLocation = x)
      } text s"The (optional) RDD vector data file to load (if it does not exist, it will be created based on the dataset)"

      opt[Int]('s', "seed") action {
        (x, c) => c.copy(seed = x)
      } text s"The random seed to initialize the topic model with (ignored when an initial model is loaded, default: ${default.seed})"

      opt[Int]('t', "topics") action {
        (x, c) => c.copy(topics = x)
      } text s"The number of topics (ignored when an initial model is loaded, default: ${default.topics})"

      opt[Int]('v', "vocabulary") action {
        (x, c) => c.copy(vocabularySize = x)
      } text s"The (maximum) size of the vocabulary (ignored when an initial model is loaded, default: ${default.vocabularySize})"

      opt[String]("checkpointSave") action {
        (x, c) => c.copy(checkpointSave = x)
      } text s"The location where checkpointed data will be stored after failure"

      opt[String]("checkpointRead") action {
        (x, c) => c.copy(checkpointRead = x)
      } text s"The location where checkpointed data will be read from as a warmstart mechanic"

      opt[Double]('α', "alpha") action {
        (x, c) => c.copy(α = x)
      } validate {
        case x => if (x > 0) success else failure("α must be larger than 0")
      } text s"The (symmetric) α prior on the topic-document distribution (default: ${default.α})"

      opt[Double]('β', "beta") action {
        (x, c) => c.copy(β = x)
      } validate {
        case x => if (x > 0) success else failure("β must be larger than 0")
      } text s"The (symmetric) β prior on the topic-word distribution (default: ${default.β})"

      opt[Int]('τ', "tau") action {
        (x, c) => c.copy(τ = x)
      } validate {
        x => if (x >= 1) success else failure("τ must be larger than or equal to 1")
      } text s"The SSP delay bound (default: ${default.τ})"

    }

    parser.parse(args, Config()) foreach run

  }

  /**
    * Runs the application with provided configuration options
    *
    * @param config The command-line arguments as a configuration
    */
  def run(config: Config): Unit = {

    // Create interfaces to spark and the parameter server
    val sc = createSparkContext()
    val gc = createGlintClient(config.glintConfig)

    // Read dictionary and dataset depending on configuration
    val (dictionary: DictionaryTF, vectors: RDD[SparseVector[Int]]) = readDictionaryAndDataset(sc, config)

    config.algorithm match {
      case "mh" => solve(config, dictionary, vectors, sc, gc, "mh")
      case "naive" => solve(config, dictionary, vectors, sc, gc, "naive")
      case "spark" => solveSpark(config, dictionary, vectors, sc, gc)
    }

    // Stop glint & spark
    gc.stop()
    sc.stop()

  }

  /**
    * Solves using Mammoth algorithm
    *
    * @param config The LDA configuration
    * @param dictionary The dictionary
    * @param vectors The data set as an RDD of sparse vectors
    * @param sc The spark context
    * @param gc The glint client
    * @param solver The solver to use
    */
  def solve(config: Config,
            dictionary: DictionaryTF,
            vectors: RDD[SparseVector[Int]],
            sc: SparkContext,
            gc: Client,
            solver: String): Unit = {

    // Create LDA configuration
    val ldaConfig = new LDAConfig()
    ldaConfig.setBlockSize(config.blockSize)
    ldaConfig.setIterations(config.iterations)
    ldaConfig.setMhSteps(config.mhSteps)
    ldaConfig.setPartitions(config.partitions)
    ldaConfig.setSeed(config.seed)
    ldaConfig.setTopics(config.topics)
    ldaConfig.setVocabularyTerms(dictionary.numFeatures)
    ldaConfig.setPowerlawCutoff(2000000 / config.topics)
    ldaConfig.setα(config.α)
    ldaConfig.setβ(config.β)
    ldaConfig.setτ(config.τ)
    ldaConfig.setCheckpointSave(config.checkpointSave)
    ldaConfig.setCheckpointRead(config.checkpointRead)

    // Print LDA config
    println(s"Computing LDA model")
    println(ldaConfig)

    // Fit data to topic model
    val topicModel = solver match {
      case "mh" => Solver.fitMetropolisHastings(sc, gc, vectors, ldaConfig)
      case "naive" => Solver.fitNaive(sc, gc, vectors, ldaConfig)
    }

    // Construct timeout and execution context for final operations
    implicit val timeout = new Timeout(300 seconds)
    implicit val ec = ExecutionContext.Implicits.global

    // Print top 50 topics
    var topicNumber = 1
    val inverseMap = dictionary.mapping.map(_.swap)
    topicModel.describe(50).foreach {
      case topicDescription =>
        println(s"Topic $topicNumber")
        topicNumber += 1
        for (i <- 0 until topicDescription.length) {
          val k = topicDescription(i)._1.toInt
          val p = topicDescription(i)._2
          println(s"   ${inverseMap(k)}:         $p")
        }
    }

    // Write to file
    if (!config.finalModel.isEmpty) {
      topicModel.writeToFile(new File(config.finalModel))
    }

    // Perform cleanup
    Await.result(topicModel.wordTopicCounts.destroy(), timeout.duration)
    Await.result(topicModel.topicCounts.destroy(), timeout.duration)

  }

  /**
    * Solves using Spark algorithm
    *
    * @param config The LDA configuration
    * @param dictionary The dictionary
    * @param vectors The data set as an RDD of sparse vectors
    * @param sc The spark context
    * @param gc The glint client
    */
  def solveSpark(config: Config, dictionary: DictionaryTF, vectors: RDD[SparseVector[Int]], sc: SparkContext,
                 gc: Client): Unit = {

    // Transform data set to spark MLLib vectors
    val sparkVectors: RDD[(Long, org.apache.spark.mllib.linalg.Vector)] = vectors.map { case x =>
      new linalg.SparseVector(x.length, x.keysIterator.toArray, x.valuesIterator.map(y => y.toDouble).toArray)
    }.zipWithIndex.map(_.swap)

    // Construct MLLib LDA model
    val sparkLDAAlgorithm = "em"
    val ldaConfig = new LDA().setK(config.topics).setAlpha(config.α + 1.0).setBeta(config.β + 1.0)
      .setSeed(config.seed).setMaxIterations(config.iterations).setOptimizer(sparkLDAAlgorithm)

    // Fit to model
    val ldaModel = sparkLDAAlgorithm match {
      case "em" =>
        val model = ldaConfig.run(sparkVectors).asInstanceOf[DistributedLDAModel]
        println(s"Log likelihood: ${model.logLikelihood}")
        model

      case "online" =>
        val model = ldaConfig.run(sparkVectors).asInstanceOf[LocalLDAModel]
        println(s"Log likelihood: ${model.logLikelihood(sparkVectors)}")
        model
    }

    // Print top 50 topics
    var topicNumber = 1
    val inverseMap = dictionary.mapping.map(_.swap)
    ldaModel.describeTopics(50).foreach {
      case (ks, ps) =>
        println(s"Topic ${topicNumber}")
        topicNumber += 1
        ks.zip(ps).foreach {
          case (k, p) =>  println(s"   ${inverseMap(k)}:         ${p}")
        }
    }

  }

  /**
    * Reads dictionary and dataset using specified configuration.
    * Depending on the settings in the configuration and the current files available it will attempt to load cached
    * copies (if specified) or compute the necessary components from scratch.
    *
    * @param sc The spark context
    * @param config The configuration
    * @return The dictionary transformer and corresponding RDD of sparse vectors (which are transformed documents)
    */
  def readDictionaryAndDataset(sc: SparkContext, config: Config): (DictionaryTF, RDD[SparseVector[Int]]) = {
    (config.rddLocation, config.dictionaryLocation) match {

      case (rddLocation, dictionaryLocation) if fileExists(sc, rddLocation) && fileExists(sc, dictionaryLocation) =>

        // Read dictionary and RDD directly from file
        (DictionaryIO.read(dictionaryLocation, config.vocabularySize), sc.objectFile(rddLocation, config.partitions))

      case (rddLocation, dictionaryLocation) =>
        // Load data set into an RDD
        val documents = CluewebReader.getDocuments(sc, config.datasetLocation, config.partitions)

        // Read or compute dictionary
        val dictionary = readDictionary(sc, documents, dictionaryLocation, config.vocabularySize)
        val dictionaryBroadcast = sc.broadcast(dictionary)

        // Vectorize data based on dictionary
        val vectors = vectorizeDataset(documents, dictionaryBroadcast).persist(StorageLevel.MEMORY_AND_DISK)

        // Store if the RDD parameter is set
        if (!rddLocation.isEmpty) {
          vectors.saveAsObjectFile(rddLocation)
        }

        (dictionary, vectors)
    }
  }

  /**
    * Reads or computes a dictionary
    *
    * @param sc The spark context
    * @param documents The documents
    * @param dictionaryLocation The dictionary location (if it exists)
    * @param vocabularySize The maximum number of vocabulary terms
    * @return A dictionary transformer
    */
  def readDictionary(sc: SparkContext,
                     documents: RDD[TokenDocument],
                     dictionaryLocation: String,
                     vocabularySize: Int): DictionaryTF = {

    dictionaryLocation match {

      // If the provided dictionary file exist, read it from disk
      case x if new java.io.File(x).exists => DictionaryIO.read(x, vocabularySize)

      // If the dictionary file name was provided, but the file does not exist, compute the dictionary and store it
      case x if x != "" => DictionaryIO.write(x, new Dictionary(vocabularySize).fit(documents))

      // No dictionary file name was provided, compute the dictionary
      case _ => new Dictionary(vocabularySize).fit(documents)
    }

  }

  /**
    * Converts given documents into sparse vector format
    *
    * @param documents The dataset
    * @param dictionary The dictionary
    * @return
    */
  def vectorizeDataset(documents: RDD[TokenDocument],
                       dictionary: Broadcast[DictionaryTF]): RDD[SparseVector[Int]] = {
    dictionary.value.transform(documents).
      map(v => v.asInstanceOf[org.apache.spark.mllib.linalg.SparseVector]).
      filter(v => v.numNonzeros > 0).
      map(v => new SparseVector[Int](v.indices, v.values.map(d => d.toInt), v.size))
  }


  /**
    * Creates a spark context
    *
    * @return The spark context
    */
  def createSparkContext(): SparkContext = {
    val sparkConf = new SparkConf().setAppName("Mammoth")
    sparkConf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    sparkConf.registerKryoClasses(Array(
      classOf[SparseVector[Int]],
      classOf[SparseVector[Long]],
      classOf[SparseVector[Double]],
      classOf[SparseVector[Float]]
    ))
    glintlda.util.registerKryo(sparkConf)
    new SparkContext(sparkConf)
  }

  /**
    * Creates a glint client
    *
    * @return The glint client
    */
  def createGlintClient(glintConfig: File): Client = {
    Client(ConfigFactory.parseFile(glintConfig))
  }

}

