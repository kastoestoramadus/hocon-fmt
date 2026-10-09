package eu.ww86.hoconfmt.interop.zio

import java.net.URI
import java.nio.file.{FileSystems, Files, Path}
import scala.jdk.CollectionConverters.*
import _root_.zio.{IO, Runtime, Unsafe}
import eu.ww86.hoconfmt.Verdict

/** The JDK's file providers raise unchecked exceptions: a path on a closed ZIP filesystem makes
  * every call on it throw `ClosedFileSystemException`. Only the JVM reaches that state, since
  * Scala Native's javalib serves no jar provider, so these cases live apart from `ZioFilesSpec`.
  */
class ZioFilesJvmSpec extends munit.FunSuite {
  def run[E, A](effect: IO[E, A]): A = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
  }

  /** Yields a path inside an archive that is closed again, and a readable file beside it. */
  def withClosedArchive(body: (Path, Path) => Unit): Unit = {
    val directory = Files.createTempDirectory("hocon-zio-jvm").nn // JDK creates a non-null path.
    val archive   = directory.resolve("files.zip").nn             // resolve returns a path.
    val system    = FileSystems.newFileSystem(URI.create("jar:" + archive.toUri), Map("create" -> "true").asJava).nn
    val closed    = system.getPath("/application.conf").nn        // getPath returns a path.
    val _         = Files.writeString(closed, "a=1")
    val readable  = directory.resolve("readable.conf").nn         // resolve returns a path.
    val _         = Files.writeString(readable, "a=1")
    system.close()
    try body(closed, readable)
    finally {
      val _ = Files.deleteIfExists(archive)
      val _ = Files.deleteIfExists(readable)
      val _ = Files.deleteIfExists(directory)
    }
  }

  test("check turns an unchecked failure into that file's outcome and continues") {
    withClosedArchive { (closed, readable) =>
      val results = run(ZioFiles.check(List(closed, readable)).runCollect)
      assertEquals(results.map(_.path).toList, List(closed, readable))
      results.toList match {
        case FileOutcome(_, Left(FileError.Io(_))) :: FileOutcome(_, Right(value)) :: Nil =>
          assertEquals(value, Verdict.of("a=1"))
        case other => fail(s"unexpected outcomes: $other")
      }
    }
  }

  test("format reports an unchecked failure as an I/O error") {
    withClosedArchive { (closed, _) =>
      run(ZioFiles.format(closed).either) match {
        case Left(FileError.Io(_)) => ()
        case other                 => fail(s"expected an I/O error, got $other")
      }
    }
  }

  test("atomic replacement reports an unchecked failure instead of dying") {
    withClosedArchive { (closed, _) =>
      assert(run(AtomicFile.write(closed, "a: 1\n").either).isLeft)
    }
  }
}
