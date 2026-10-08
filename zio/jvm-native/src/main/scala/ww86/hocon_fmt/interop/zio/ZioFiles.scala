package ww86.hocon_fmt.interop.zio

import java.io.IOException
import java.nio.file.{Files, Path}
import _root_.zio.{IO, ZIO}
import _root_.zio.stream.ZStream
import ww86.hocon_fmt.{Refusal, Verdict}

/** Refusals are decisions about content; I/O errors are failures to access a file. */
enum FileError derives CanEqual {
  case Refused(reason: Refusal)
  case Io(cause: IOException)
}

final case class FileOutcome(path: Path, result: Either[FileError, Verdict]) derives CanEqual

object ZioFiles {
  def verdict(path: Path): IO[FileError, Verdict] =
    ZIO.attemptBlockingIO(Files.readAllBytes(path)).mapError(FileError.Io(_)).flatMap { bytes =>
      Verdict.of(bytes) match {
        case Verdict.Refused(reason) => ZIO.fail(FileError.Refused(reason))
        case decision                => ZIO.succeed(decision)
      }
    }

  /** Returns the original decision; NeedsFormatting means the replacement completed. */
  def format(path: Path): IO[FileError, Verdict] =
    ZIO.attemptBlockingIO(path.toRealPath()).mapError(FileError.Io(_)).flatMap { target =>
      verdict(target).flatMap {
        case decision @ Verdict.NeedsFormatting(text) =>
          AtomicFile.write(target, text).mapError(FileError.Io(_)).as(decision)
        case decision => ZIO.succeed(decision)
      }
    }

  /** Each input produces one outcome in order, so a refusal or unreadable file cannot stop a run. */
  def check(paths: Iterable[Path]): ZStream[Any, Nothing, FileOutcome] =
    ZStream.fromIterable(paths).mapZIO { path =>
      verdict(path).either.map(FileOutcome(path, _))
    }
}
