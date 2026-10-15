package eu.ww86.hoconfmt

import _root_.cats.effect.IO
import _root_.cats.syntax.all.*
import fs2.io.file.Path

/** The contract the CLI's run depends on, on every platform: FileTraversal returns one result
  * per file, in the list's order — exactly what running the files one at a time gives. The JVM
  * and Scala.js link a parallel FileTraversal, Scala Native a sequential one; this suite runs
  * against whichever its platform compiled, with a sequential reference beside it.
  */
class FileTraversalSpec extends munit.CatsEffectSuite {

  private val files = (1 to 64).toList.map(i => (Path(s"f$i.conf"), i))

  test("results equal one-at-a-time, in the list's order") {
    for {
      oneAtATime <- files.traverse(entry => IO(entry._2))
      traversed  <- FileTraversal(files)(entry => IO.pure(entry._2))
    } yield assertEquals(traversed, oneAtATime)
  }

  test("every file is visited exactly once") {
    for {
      seen      <- IO.ref(List.empty[Int])
      traversed <- FileTraversal(files)(entry => seen.update(entry._2 :: _).as(entry._2))
      collected <- seen.get
    } yield {
      assertEquals(traversed, files.map(_._2))
      assertEquals(collected.sorted, files.map(_._2))
    }
  }
}
