package eu.ww86.hoconfmt

/** The Scala.js half of the timeout guard: no second thread exists to give a regressing call up,
  * so the call runs plain and the after-the-fact bounds in the callers answer once it returns.
  * The JVM object of the same name is the one that can actually cut a call off.
  */
object TimeoutGuard {
  def within[A](boundMs: Long)(run: => A): A = {
    val _ = boundMs // no second thread on Scala.js: nothing to time out against
    run
  }
}
