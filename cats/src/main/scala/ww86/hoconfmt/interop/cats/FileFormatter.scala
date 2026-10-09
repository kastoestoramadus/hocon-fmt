package ww86.hoconfmt.interop.cats

import _root_.cats.effect.Async
import _root_.cats.syntax.all.*
import fs2.{Pipe, Stream}
import fs2.io.file.{Files, Path}
import ww86.hoconfmt.{DuplicateReport, Finding, FormatOptions, FormatRefusedException, Refusal, Verdict}

import java.nio.charset.StandardCharsets.UTF_8

/** Result of formatting a file in place. IO failures remain in the effect's error channel. */
enum FormatOutcome derives CanEqual {
  case Formatted
  case AlreadyFormatted
  case Refused(refusal: Refusal)
}

/** What one read of a file made of it: the verdict every integration acts on, and the duplicate
  * report of the same text. The report is empty when the text is not HOCON this formatter reads,
  * or is named as another format. A failed report is retained separately so callers can warn
  * without treating failure as a successful empty report.
  */
final case class Inspection(verdict: Verdict, findings: List[Finding], reportFailure: Option[Refusal] = None)
    derives CanEqual

object Inspection {

  /** What one text is, from bytes already in hand: the verdict, and the report of the text. Bytes
    * that do not decode, or a name promising another format, are not HOCON this formatter reads,
    * so they have nothing to report.
    */
  def of(content: Array[Byte], name: String, options: FormatOptions): Inspection = {
    val verdict = Verdict.of(content, name, options)
    val report  = verdict match {
      case Verdict.Refused(Refusal.NotUtf8) | Verdict.Refused(Refusal.OtherFormat(_)) => Right(Nil)
      case _                                                                          => DuplicateReport.findings(new String(content, UTF_8), name)
    }
    report.fold(
      refusal => Inspection(verdict, Nil, Some(refusal)),
      findings => Inspection(verdict, findings)
    )
  }
}

/** File operations for JVM, Node and Native callers. Supply Files explicitly to choose a filesystem.
  * Calls on the same file must be serialised by the caller.
  */
final class FileFormatter[F[_]: Async](using files: Files[F]) {

  /** The path is what the file's verdict is named by, and what a refusal reports. */
  def verdict(path: Path): F[Verdict] =
    verdict(path, FormatOptions.default)

  /** As [[verdict(Path)]], with the style options the caller asks for. */
  def verdict(path: Path, options: FormatOptions): F[Verdict] =
    files.readAll(path).compile.to(Array).map(Verdict.of(_, path.toString, options))

  /** The verdict and the duplicate report, from one read of the file, of its target if it is a
    * symlink, as [[format]] writes to.
    */
  def inspect(path: Path): F[Inspection] =
    inspect(path, FormatOptions.default)

  /** As [[inspect(Path)]], with the style options the caller asks for. */
  def inspect(path: Path, options: FormatOptions): F[Inspection] =
    files.realPath(path).flatMap { target =>
      files.readAll(target).compile.to(Array).map(bytes => Inspection.of(bytes, target.toString, options))
    }

  /** Replaces the file with text the verdict of the same read said was needed. */
  def write(path: Path, content: String): F[Unit] =
    files.realPath(path).flatMap(replace(_, content))

  /** Canonicalises and deduplicates aliases before parallel work. Missing paths are retained so
    * callers can report their IO errors alongside the other files.
    */
  def distinctPaths(paths: List[Path]): F[List[Path]] =
    paths.traverse(path => files.realPath(path).handleError(_ => path.absolute)).map(_.distinct)

  /** Checks in stream order, without writing; a refusal is a verdict, not an IO failure. */
  def check: Pipe[F, Path, (Path, Verdict)] =
    _.evalMap(path => verdict(path).tupleLeft(path))

  /** Only NeedsFormatting is written. The original is replaced by a staged copy only when that
    * copy provably keeps the file's identity — its owner, group and every mode bit; otherwise the
    * formatted text is written in place, which loses the crash-atomicity of a rename but keeps the
    * file the same inode, as it was before this adapter. Symlinks are followed so the link itself
    * survives.
    */
  def format(path: Path): F[FormatOutcome] =
    format(path, FormatOptions.default)

  /** As [[format(Path)]], with the style options the caller asks for. */
  def format(path: Path, options: FormatOptions): F[FormatOutcome] =
    files.realPath(path).flatMap { target =>
      verdict(target, options).flatMap {
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
    replaceWithStaged(target, content).flatMap {
      case true  => ().pure[F]
      case false => writeInPlace(target, content)
    }

  /** True when the target was replaced by a staged copy carrying its identity. A file or
    * directory that cannot be written is not replaced: the write in place then fails the way it
    * always did, rather than a read-only file changing under its owner.
    */
  private def replaceWithStaged(target: Path, content: String): F[Boolean] =
    target.parent match {
      case None         => false.pure[F]
      case Some(parent) =>
        for {
          writable       <- files.isWritable(target)
          parentWritable <- files.isWritable(parent)
          identity       <- AtomicFiles.identity(target)
          replaced       <- (writable, parentWritable, identity) match {
                        case (true, true, Some(kept)) => stage(parent, target, content, kept)
                        case _                        => false.pure[F]
                      }
        } yield replaced
    }

  private def stage(parent: Path, target: Path, content: String, identity: FileIdentity): F[Boolean] =
    files.tempDirectory(Some(parent), ".hocon-fmt-", None).use { directory =>
      val temporary = directory / target.fileName

      for {
        _    <- files.createFile(temporary)
        _    <- Stream.emit(content).through(files.writeUtf8(temporary)).compile.drain
        kept <- AtomicFiles.keepIdentity(temporary, identity)
        _    <- if kept then AtomicFiles.move(files, temporary, target) else ().pure[F]
      } yield kept
    }

  // Truncate and write, as the CLI did before this adapter. The formatted text was verified
  // before either write, so a refusal never reaches here.
  private def writeInPlace(target: Path, content: String): F[Unit] =
    Stream.emit(content).through(files.writeUtf8(target)).compile.drain
}

object FileFormatter {
  def apply[F[_]: Async: Files]: FileFormatter[F] = new FileFormatter[F]
}
