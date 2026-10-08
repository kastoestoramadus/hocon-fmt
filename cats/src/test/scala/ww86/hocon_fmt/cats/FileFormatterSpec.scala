package ww86.hocon_fmt.cats

import java.nio.charset.StandardCharsets.UTF_8

import cats.effect.IO
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import fs2.io.file.{Files, Path, PosixPermission}
import ww86.hocon_fmt.{Refusal, Verdict}

class FileFormatterSpec extends munit.CatsEffectSuite {
  val tmp       = ResourceFunFixture(Files[IO].tempDirectory)
  val formatter = FileFormatter[IO]

  def write(dir: Path, name: String, bytes: Array[Byte]): IO[Path] =
    Stream.chunk(Chunk.array(bytes)).through(Files[IO].writeAll(dir / name)).compile.drain.as(dir / name)

  def read(file: Path): IO[List[Byte]] = Files[IO].readAll(file).compile.toList

  tmp.test("verdict and check read every file without writing") { dir =>
    for {
      a       <- write(dir, "a.conf", "a=1".getBytes(UTF_8))
      b       <- write(dir, "b.conf", "b: 2\n".getBytes(UTF_8))
      v       <- formatter.verdict(a)
      results <- Stream.emits(List(a, b)).covary[IO].through(formatter.check).compile.toList
      bytes   <- read(a)
    } yield {
      assertEquals(v, Verdict.NeedsFormatting("a: 1\n"))
      assertEquals(results, List(a -> v, b -> Verdict.AlreadyFormatted))
      assertEquals(bytes, "a=1".getBytes(UTF_8).toList)
    }
  }

  tmp.test("format writes complete output, is idempotent, and removes its temporary file") { dir =>
    for {
      file    <- write(dir, "a.conf", "a=1".getBytes(UTF_8))
      first   <- formatter.format(file)
      bytes   <- read(file)
      second  <- formatter.format(file)
      entries <- Files[IO].list(dir).compile.toList
    } yield {
      assertEquals(first, FormatOutcome.Formatted)
      assertEquals(bytes, "a: 1\n".getBytes(UTF_8).toList)
      assertEquals(second, FormatOutcome.AlreadyFormatted)
      assertEquals(entries, List(file))
    }
  }

  tmp.test("refused input is never written, including invalid UTF-8") { dir =>
    List("a: ${".getBytes(UTF_8), Array(0xff.toByte)).traverse_ { source =>
      for {
        file    <- write(dir, "refused.conf", source)
        outcome <- formatter.format(file)
        bytes   <- read(file)
      } yield {
        assert(outcome match {
          case FormatOutcome.Refused(_) => true
          case _                        => false
        })
        assertEquals(bytes, source.toList)
      }
    }
  }

  tmp.test("error-channel variant carries the refusal and leaves bytes intact") { dir =>
    for {
      file   <- write(dir, "bad.conf", Array(0xff.toByte))
      result <- formatter.formatOrRaise(file).attempt
      bytes  <- read(file)
    } yield {
      result match {
        case Left(error: FormatRefusedException) =>
          assertEquals(error.refusal, Refusal.NotUtf8)
          assertEquals(error.getMessage, Refusal.NotUtf8.reason)
        case other => fail(s"expected refusal exception, got $other")
      }
      assertEquals(bytes, List(0xff.toByte))
    }
  }

  tmp.test("unreadable files stay in the IO error channel") { dir =>
    formatter.verdict(dir / "missing.conf").attempt.map(result => assert(result.isLeft))
  }
  tmp.test("canonical paths deduplicate aliases and retain missing files for reporting") { dir =>
    for {
      file      <- write(dir, "a.conf", "a=1".getBytes(UTF_8))
      canonical <- Files[IO].realPath(file)
      paths     <- formatter.distinctPaths(List(file, dir / "." / "a.conf", dir / "missing.conf"))
    } yield assertEquals(paths, List(canonical, (dir / "missing.conf").absolute))
  }

  tmp.test("format follows symlinks and preserves permissions") { dir =>
    for {
      file                <- write(dir, "target.conf", "a=1".getBytes(UTF_8))
      canonical           <- Files[IO].realPath(file)
      originalPermissions <- Files[IO].getPosixPermissions(file)
      permissions          = originalPermissions.add(PosixPermission.OwnerExecute)
      _                   <- Files[IO].setPosixPermissions(file, permissions)
      link                 = dir / "link.conf"
      _                   <- Files[IO].createSymbolicLink(link, canonical)
      outcome             <- formatter.format(link)
      isLink              <- Files[IO].isSymbolicLink(link)
      after               <- Files[IO].getPosixPermissions(file)
      bytes               <- read(file)
    } yield {
      assertEquals(outcome, FormatOutcome.Formatted)
      assert(isLink)
      assertEquals(after, permissions)
      assertEquals(bytes, "a: 1\n".getBytes(UTF_8).toList)
    }
  }

}
