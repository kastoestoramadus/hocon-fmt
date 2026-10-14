package eu.ww86.hoconfmt

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationLong

/** The real timeout behind the property bounds: the guarded call runs on a worker thread and the
  * test thread gives up at the bound, so a regression fails the suite in seconds instead of
  * hanging it for as long as the slow call takes. The Scala.js object of the same name runs the
  * call plain, since no second thread exists there to give up; the after-the-fact bounds in the
  * callers answer once a regressing call does return.
  */
object TimeoutGuard {
  def within[A](boundMs: Long)(run: => A): A =
    Await.result(Future(run)(using ExecutionContext.global), boundMs.milliseconds)
}
