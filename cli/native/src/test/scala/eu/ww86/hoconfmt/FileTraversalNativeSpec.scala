package eu.ww86.hoconfmt

import _root_.cats.effect.IO
import _root_.cats.syntax.all.*
import scala.concurrent.duration.*

/** Scala Native examines one file at a time because concurrent fibres crash the runtime
  * (docs/limitations.md). The shared suite cannot see that choice: a parallel loop returns the
  * same results, so this pins the in-flight count instead.
  */
class FileTraversalNativeSpec extends munit.CatsEffectSuite {

  private val files = (1 to 16).toList

  test("at most one file is in flight at a time") {
    (IO.ref(0), IO.ref(0)).tupled.flatMap { case (inFlight, peak) =>
      val examine = (_: Int) =>
        inFlight.updateAndGet(_ + 1).flatMap(n => peak.update(_ max n)) *>
          IO.sleep(20.millis) *>
          inFlight.update(_ - 1)
      FileTraversal(files)(examine) *> peak.get.map(assertEquals(_, 1))
    }
  }
}
