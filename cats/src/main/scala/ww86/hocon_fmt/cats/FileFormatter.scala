package ww86.hocon_fmt.cats

import _root_.cats.effect.Async
import _root_.cats.syntax.all.*
import fs2.{Pipe, Stream}
import fs2.io.file.{Files, Path}
import ww86.hocon_fmt.{Refusal, Verdict}

/** Result of formatting a file in place. IO failures remain in the effect's error channel. */
enum FormatOutcome derives CanEqual {
  case Formatted
  case AlreadyFormatted
  case Refused(refusal: Refusal)
}

/** Opt-in error-channel representation of a safe-formatting refusal. */
final class FormatRefusedException(val refusal: Refusal) extends Exception(refusal.reason)

/** File operations for JVM, Node and Native callers. Supply Files explicitly to choose a filesystem.
  * Calls on the same file must be serialised by the caller.
  */
final class FileFormatter[F[_]: Async](using files: Files[F]) {

  def verdict(path: Path): F[Verdict] =
    files.readAll(path).compile.to(Array).map(Verdict.of)

  /** Canonicalises and deduplicates aliases before parallel work. Missing paths are retained so
    * callers can report their IO errors alongside the other files.
    */
  def distinctPaths(paths: List[Path]): F[List[Path]] =
    paths.traverse(path => files.realPath(path).handleError(_ => path.absolute)).map(_.distinct)

  /** Checks in stream order, without writing; a refusal is a verdict, not an IO failure. */
  def check: Pipe[F, Path, (Path, Verdict)] =
    _.evalMap(path => verdict(path).tupleLeft(path))

  /** Only NeedsFormatting is written. The original survives failed or cancelled staged writes;
    * replacement requires an atomic move, with no fallback to a non-atomic overwrite.
    * Symlinks are followed so the link itself survives.
    */
  def format(path: Path): F[FormatOutcome] =
    files.realPath(path).flatMap { target =>
      verdict(target).flatMap {
        case Verdict.AlreadyFormatted         => FormatOutcome.AlreadyFormatted.pure[F]
        case Verdict.Refused(refusal)         => FormatOutcome.Refused(refusal).pure[F]
        case Verdict.NeedsFormatting(content) => replace(target, content).as(FormatOutcome.Formatted)
      }
    }

  /** Same operation as format, raising only refusals as FormatRefusedException. */
  def formatOrRaise(path: Path): F[FormatOutcome] =
    format(path).flatMap {
      case FormatOutcome.Refused(refusal) => Async[F].raiseError(new FormatRefusedException(refusal))
      case outcome                        => outcome.pure[F]
    }

  private def replace(target: Path, content: String): F[Unit] =
    files.tempDirectory(target.parent, ".hocon-fmt-", None).use { directory =>
      val temporary = directory / target.fileName

      // Copy attributes before writing so replacement retains the original file's permissions.
      files.createFile(temporary) *>
        AtomicFiles.copyAttributes(files, target, temporary) *>
        Stream.emit(content).through(files.writeUtf8(temporary)).compile.drain *>
        AtomicFiles.move(files, temporary, target)
    }
}

object FileFormatter {
  def apply[F[_]: Async: Files]: FileFormatter[F] = new FileFormatter[F]
}
