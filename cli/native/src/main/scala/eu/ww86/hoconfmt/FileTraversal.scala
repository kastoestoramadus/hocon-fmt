package eu.ww86.hoconfmt

import _root_.cats.effect.IO
import _root_.cats.syntax.all.*

/** Examines the selected files one at a time, in the list's order. Concurrent fibres on Scala
  * Native 0.5.12's worker threads (`parTraverse`) die with signal 11 in about 4% of 1000-file
  * `--check` runs, and a standalone cats-effect program crashes too. The root cause is not
  * established; one file at a time measured far lower, not zero (docs/limitations.md). The JVM
  * and Scala.js examine concurrently.
  */
private[hoconfmt] object FileTraversal {

  def apply[A, B](files: List[A])(f: A => IO[B]): IO[List[B]] =
    files.traverse(f)
}
