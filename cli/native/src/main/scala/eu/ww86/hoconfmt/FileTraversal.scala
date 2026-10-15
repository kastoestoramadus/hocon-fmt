package eu.ww86.hoconfmt

import _root_.cats.effect.IO
import _root_.cats.syntax.all.*

/** Examines the selected files one at a time, in the list's order. Scala Native 0.5.12's
  * collector corrupts the heap when several fibres allocate on it at once — signal 11 in about
  * 4% of 1000-file `--check` runs — so the Native CLI gives up the parallel loop until upstream
  * fixes it (docs/limitations.md); the JVM and Scala.js examine concurrently.
  */
private[hoconfmt] object FileTraversal {

  def apply[A, B](files: List[A])(f: A => IO[B]): IO[List[B]] =
    files.traverse(f)
}
