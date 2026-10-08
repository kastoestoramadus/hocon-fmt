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

  def withFile(bytes: Array[Byte], name: String = "application.conf")(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("hocon-zio").nn // JDK creates a non-null path.
    val path      = directory.resolve(name).nn                // resolve returns a path.
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
    withFile(bytes("a = 1\n")) { path =>
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
          // The file's own name is what the decision is made under, so the refusal reports it.
          case Left(FileError.Refused(reason)) =>
            assertEquals(Verdict.Refused(reason), Verdict.of(original, path.toString))
          case other => fail(s"expected refusal, got $other")
        }
        assertEquals(Files.readAllBytes(path).toSeq, original.toSeq)
        assertEquals(Files.getLastModifiedTime(path), before)
      }
    }
  }

  // Lightbend's loader reads .json and .properties too; a round trip hands back HOCON, not the
  // file its name promises, so the name alone decides, whatever the content.
  test("a file named as another format is refused, and left alone") {
    withFile(bytes("a: 1\n"), "application.json") { path =>
      val before = Files.getLastModifiedTime(path)
      val reason = Refusal.OtherFormat("JSON")
      assertEquals(run(ZioFiles.verdict(path).flip), FileError.Refused(reason))
      assertEquals(run(ZioFiles.format(path).flip), FileError.Refused(reason))
      assertEquals(Files.readAllBytes(path).toSeq, bytes("a: 1\n").toSeq)
      assertEquals(Files.getLastModifiedTime(path), before)
    }
  }

  test("a refusal names the file it came from") {
    withFile(bytes("a={")) { path =>
      run(ZioFiles.verdict(path).flip) match {
        case FileError.Refused(Refusal.NotHocon(detail)) => assert(detail.startsWith(path.toString + ":"), detail)
        case other                                       => fail(s"expected a HOCON refusal, got $other")
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

  // A replacement may only change the file's identity when it can be kept: the same owner, group
  // and mode bits, setgid included. Otherwise the file is written in place.
  test("a replacement keeps the owner, group and every mode bit of the file") {
    withFile(bytes("a=1")) { path =>
      TestPosix.chmod(path, 0x5a0) // 02640: setgid, rw-r-----
      val before = TestPosix.stat(path)
      val _      = run(ZioFiles.format(path))
      assertEquals(TestPosix.stat(path), before)
      assertEquals(Verdict.of(Files.readAllBytes(path)), Verdict.AlreadyFormatted)
    }
  }

  // The group is not the process's primary one, so a staged copy would silently change it.
  test("a replacement keeps a group that is only a supplementary group of this process") {
    withFile(bytes("a=1")) { path =>
      TestPosix.otherGroup(path).foreach { group =>
        val _ = TestPosix.chgrp(path, group)
        TestPosix.chmod(path, 0x1a0) // 0640: rw-r-----
        val before = TestPosix.stat(path)
        val _      = run(ZioFiles.format(path))
        assertEquals(TestPosix.stat(path), before)
        assertEquals(Verdict.of(Files.readAllBytes(path)), Verdict.AlreadyFormatted)
      }
    }
  }

  // The identity tests above would also pass if the file were written in place, so only an
  // assertion on the inode pins that the rename still happens where it can.
  test("a writable file in a writable directory is replaced by a rename") {
    withFile(bytes("a=1")) { path =>
      val before = TestPosix.ino(path)
      val _      = run(ZioFiles.format(path))
      assertNotEquals(TestPosix.ino(path), before, "inode unchanged: the file was written in place")
      assertEquals(Verdict.of(Files.readAllBytes(path)), Verdict.AlreadyFormatted)
    }
  }

  // The documented consequence of the rename: the hard link keeps pointing at the old inode, so
  // it holds the old content and the formatted file has none of its links left.
  test("a replacement leaves a hard link holding the old content") {
    withFile(bytes("a=1")) { path =>
      val sibling = path.resolveSibling("hardlinked.conf").nn // resolveSibling returns a path.
      val _       = Files.createLink(sibling, path)
      assertEquals(TestPosix.nlink(path), 2L)
      val _ = run(ZioFiles.format(path))
      assertEquals(TestPosix.nlink(path), 1L, "the file still shares its inode: it was written in place")
      assertEquals(Files.readAllBytes(sibling).toSeq, bytes("a=1").toSeq)
      assertEquals(Verdict.of(Files.readAllBytes(path)), Verdict.AlreadyFormatted)
    }
  }

  // The file is writable but no staged copy can be put beside it, so the write has to happen in
  // place, as it did before this adapter.
  test("a writable file in a directory that cannot be written is still formatted") {
    val directory = Files.createTempDirectory("hocon-zio").nn // JDK creates a non-null path.
    val locked    = directory.resolve("locked").nn            // resolve returns a path.
    val file      = locked.resolve("a.conf").nn               // resolve returns a path.
    try {
      val _ = Files.createDirectory(locked)
      val _ = Files.write(file, bytes("a=1"))
      TestPosix.chmod(locked, 0x16d) // 0555
      try {
        assertEquals(run(ZioFiles.format(file)), Verdict.of("a=1"))
        assertEquals(Verdict.of(Files.readAllBytes(file)), Verdict.AlreadyFormatted)
      } finally TestPosix.chmod(locked, 0x1ed) // 0755, so the fixture can delete it
    } finally {
      val _ = Files.deleteIfExists(file)
      val _ = Files.deleteIfExists(locked)
      val _ = Files.deleteIfExists(directory)
    }
  }

  // A read-only file is not replaced either, the way an in-place write refuses it. Root writes
  // any file, so there the replacement is allowed.
  test("a read-only file is left unchanged") {
    withFile(bytes("a=1")) { path =>
      TestPosix.chmod(path, 0x124) // 0444
      val writable = Files.isWritable(path)
      val result   = run(ZioFiles.format(path).either)
      if writable then assertEquals(result, Right(Verdict.of("a=1")))
      else {
        assert(result.isLeft)
        assertEquals(Files.readAllBytes(path).toSeq, bytes("a=1").toSeq)
      }
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
