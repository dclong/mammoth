package ch.ethz.inf.da.mammoth.topicmodeling

/**
 * Base abstraction for a solver using the Expectation-Maximization algorithm.
 *
 * @param iterations The number of iterations
 */
abstract class EMSolver(val iterations:Int) extends Serializable {

  /**
   * Runs the EM algorithm by iteratively calling <code>EStep()</code> and <code>MStep()</code> while checking for
   * convergence by calling the <code>converged()</code> function. It limits the number of iterations by the passed
   * constructor variable <code>iterations</code>.
   */
  def solve(): Unit = {
    var t = 1
    while(t < iterations && !converged()) {
      println(s"Local iteration $t")
      this.EStep()
      this.MStep()
      t += 1
    }
  }

  /**
   * Checks for convergence. A subclass can implement this function to check for convergence to stop iterating earlier.
   *
   * @return Whether the solver has converged.
   */
  def converged(): Boolean = false

  /**
   * Performs a single EStep.
   */
  def EStep(): Unit

  /**
   * Performs a single MStep.
   */
  def MStep(): Unit

}
