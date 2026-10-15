package eu.ww86.hoconfmt

import _root_.cats.effect.IO
import _root_.cats.syntax.all.*

/** Examines the selected files concurrently, one fibre per file. The results keep the list's
  * order, so the run's output stays deterministic in file order; the Scala Native CLI builds a
  * sequential FileTraversal instead (docs/limitations.md).
  */
private[hoconfmt] object FileTraversal {

  def apply[A, B](files: List[A])(f: A => IO[B]): IO[List[B]] =
    files.parTraverse(f)
}
