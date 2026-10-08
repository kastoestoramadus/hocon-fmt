package ww86.hocon_fmt.interop.zio

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import java.nio.charset.StandardCharsets.UTF_8
import _root_.zio.{IO, Runtime, Unsafe}
import ww86.hocon_fmt.{Refusal, Verdict}

class ZioFilesSpec extends munit.FunSuite {
  def run[E, A](effect: IO[E, A]): A = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
  }

  def withFile(bytes: Array[Byte])(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("hocon-zio").nn // JDK creates a non-null path.
    val path      = directory.resolve("application.conf").nn  // resolve returns a path.
    val _         = Files.write(path, bytes)
    try body(path)
    finally {
      val entries = Files.list(directory).nn // list returns an open stream.
      try
        entries.forEach { entry =>
          val _ = Files.deleteIfExists(entry)
        }
      finally entries.close()
      val _ = Files.deleteIfExists(directory)
    }
  }

  def bytes(text: String): Array[Byte] = text.getBytes(UTF_8)

  test("file verdict checks bytes without writing") {
    withFile(bytes("a=1")) { path =>
      assertEquals(run(ZioFiles.verdict(path)), Verdict.of("a=1"))
      assertEquals(Files.readAllBytes(path).toSeq, bytes("a=1").toSeq)
    }
  }

  test("format replaces a file with complete output and leaves no temporary file") {
    withFile(bytes("a=1")) { path =>
      assertEquals(run(ZioFiles.format(path)), Verdict.of("a=1"))
      assertEquals(Verdict.of(Files.readAllBytes(path)), Verdict.AlreadyFormatted)
      val entries = Files.list(path.getParent)
      assertEquals(entries.nn.count(), 1L) // list returns an open stream.
      entries.nn.close()                   // The same non-null stream is closed after counting.
    }
  }

  test("already formatted files are never replaced") {
    withFile(bytes("a: 1\n")) { path =>
      val before = Files.getLastModifiedTime(path)
      assertEquals(run(ZioFiles.format(path)), Verdict.AlreadyFormatted)
      assertEquals(Files.getLastModifiedTime(path), before)
    }
  }

  test("refused HOCON and invalid UTF-8 remain byte-for-byte untouched") {
    List(bytes("a={"), Array[Byte](0xc3.toByte, 0x28.toByte)).foreach { original =>
      withFile(original) { path =>
        val before = Files.getLastModifiedTime(path)
        run(ZioFiles.format(path).either) match {
          case Left(FileError.Refused(reason)) => assertEquals(Verdict.Refused(reason), Verdict.of(original))
          case other                           => fail(s"expected refusal, got $other")
        }
        assertEquals(Files.readAllBytes(path).toSeq, original.toSeq)
        assertEquals(Files.getLastModifiedTime(path), before)
      }
    }
  }

  test("atomic replacement failure leaves the destination and removes the staged file") {
    withFile(bytes("original")) { path =>
      val directory = path.getParent.nn // A temporary file always has a parent.
      val result    = run(AtomicFile.write(directory, "a: 1\n").either)
      assert(result.isLeft)
      assertEquals(Files.readAllBytes(path).toSeq, bytes("original").toSeq)
      val entries = Files.list(directory).nn // list returns an open stream.
      try assertEquals(entries.count(), 1L)
      finally entries.close()
    }
  }

  test("format follows a symbolic link and retains POSIX permissions") {
    withFile(bytes("a=1")) { path =>
      val link        = path.resolveSibling("linked.conf").nn // resolveSibling returns a path.
      val _           = Files.createSymbolicLink(link, path)
      val permissions = Files.getPosixFilePermissions(path)
      assertEquals(run(ZioFiles.format(link)), Verdict.of("a=1"))
      assertEquals(Files.readSymbolicLink(link), path)
      assertEquals(Files.getPosixFilePermissions(path).asScala.toSet, permissions.asScala.toSet)
      assertEquals(Verdict.of(Files.readAllBytes(path)), Verdict.AlreadyFormatted)
    }
  }

  test("check streams every path including I/O errors and refusals without writing") {
    withFile(bytes("a=1")) { path =>
      val missing = path.resolveSibling("missing.conf").nn // resolveSibling returns a path.
      val results = run(ZioFiles.check(List(missing, path)).runCollect)
      assertEquals(results.map(_.path).toList, List(missing, path))
      results.toList match {
        case FileOutcome(_, Left(FileError.Io(_))) :: FileOutcome(_, Right(value)) :: Nil =>
          assertEquals(value, Verdict.of("a=1"))
        case other => fail(s"unexpected outcomes: $other")
      }
      assertEquals(Files.readAllBytes(path).toSeq, bytes("a=1").toSeq)
    }
    withFile(Array[Byte](0xff.toByte)) { path =>
      val results = run(ZioFiles.check(List(path)).runCollect)
      assertEquals(results.toList, List(FileOutcome(path, Left(FileError.Refused(Refusal.NotUtf8)))))
    }
  }
}
