package ww86.hocon_fmt

import java.nio.charset.StandardCharsets.UTF_8

import _root_.cats.data.Validated
import _root_.cats.effect.std.Console
import _root_.cats.effect.{ExitCode, IO, IOApp}
import _root_.cats.syntax.all.*
import com.monovore.decline.{Command, Help, Opts, PlatformApp}
import fs2.io.file.Path
import ww86.hocon_fmt.cats.{FileFormatter, FormatOutcome}

/** Command line entry point: parses arguments, delegates file operations to [[FileFormatter]],
  * and prints the results. The same code runs as a native binary, a Node script and a JVM program.
  */
object CmdApi extends IOApp {

  final case class Arguments(files: List[Path], checkOnly: Boolean)

  enum Invocation derives CanEqual {
    case FileMode(arguments: Arguments)
    case Stdin(filename: String)
    case Version
  }

  val command: Command[Invocation] =
    Command(
      name = "hocon-fmt",
      header = "Formats HOCON files in place. Files it cannot format safely are left alone."
    ) {
      (
        Opts.arguments[String]("file").orEmpty.map(_.map(Path(_))),
        Opts
          .flag(
            "check",
            "Report unformatted files instead of rewriting them; exit 1 if any are found.",
            short = "c"
          )
          .orFalse,
        Opts.flag("stdin", "Read UTF-8 from stdin and write only formatted text to stdout.").orFalse,
        Opts.option[String]("stdin-filename", "Name used in stdin diagnostics; no file is read or written.").orNone,
        Opts.flag("version", "Print the formatter version and exit.").orFalse
      ).tupled.mapValidated { case (files, checkOnly, stdin, filename, version) =>
        if (version && (files.nonEmpty || checkOnly || stdin || filename.nonEmpty))
          Validated.invalidNel("--version cannot be combined with formatting arguments.")
        else if (version) Validated.validNel(Invocation.Version)
        else if (stdin && (files.nonEmpty || checkOnly))
          Validated.invalidNel("--stdin cannot be combined with files or --check.")
        else if (stdin) Validated.validNel(Invocation.Stdin(filename.getOrElse("<stdin>")))
        else if (filename.nonEmpty) Validated.invalidNel("--stdin-filename requires --stdin.")
        else if (files.isEmpty) Validated.invalidNel("At least one file or --stdin is required.")
        else Validated.validNel(Invocation.FileMode(Arguments(files, checkOnly)))
      }
    }

  enum Outcome {
    case Rewritten(path: String)
    case AlreadyFormatted(path: String)
    case NeedsFormatting(path: String, formatted: String)
    case Unformattable(path: String, reason: String)
  }

  /** Every file's outcome, and what the process prints and exits with because of them. */
  final case class Run(outcomes: List[Outcome]) {

    // 1 rather than -1: an exit status is a byte, so -1 would reach the shell as 255.
    def exitCode: ExitCode =
      if (outcomes.exists { case _: Outcome.NeedsFormatting => true; case _ => false }) ExitCode(1)
      else ExitCode.Success

    def rendered: String =
      (s"Running HOCON formatter for ${outcomes.size} files.\n" :: outcomes.map(render)).mkString
  }

  // Scala.js hands `main` no arguments; under Node they are in `process.argv`.
  override def run(args: List[String]): IO[ExitCode] =
    command.parse(PlatformApp.ambientArgs.getOrElse(args)) match {
      case Right(Invocation.FileMode(arguments)) =>
        examineAll(arguments).flatTap(run => IO.print(run.rendered)).map(_.exitCode)
      case Right(Invocation.Version)         => IO.println(s"hocon-fmt ${BuildInfo.version}").as(ExitCode.Success)
      case Right(Invocation.Stdin(filename)) =>
        StdStreams.readStdin
          .map(formatStdin(_, filename))
          .handleError { e =>
            StdinResult("", s"cannot read $filename: ${Option(e.getMessage).getOrElse(e.toString)}\n", ExitCode(2))
          }
          .flatMap(result =>
            StdStreams.writeStdout(result.stdout) *> Console[IO].error(result.stderr).as(result.exitCode)
          )
      case Left(help) => usage(help)
    }

  final case class StdinResult(stdout: String, stderr: String, exitCode: ExitCode)

  def formatStdin(content: Array[Byte], filename: String): StdinResult =
    Verdict.of(content) match {
      case Verdict.NeedsFormatting(formatted) => StdinResult(formatted, "", ExitCode.Success)
      case Verdict.AlreadyFormatted           => StdinResult(String(content, UTF_8), "", ExitCode.Success)
      case Verdict.Refused(refusal)           =>
        StdinResult("", s"cannot format $filename: ${refusal.reason}\n", ExitCode(1))
    }

  /** Examines every file, even after an unformatted one is found, and each file once.
    *
    * Exiting from inside a parallel loop used to kill the JVM mid-iteration, so `--check` could
    * miss files entirely; now nothing exits until every outcome is in. And a file named twice,
    * however spelled, would be written by two fibers at once, so files are told apart by their
    * canonical path.
    */
  def examineAll(arguments: Arguments): IO[Run] =
    formatter
      .distinctPaths(arguments.files)
      .flatMap(_.parTraverse(file => examine(file, file.toString, arguments.checkOnly)))
      .map(Run(_))

  private val formatter = FileFormatter[IO]

  private def examine(file: Path, path: String, checkOnly: Boolean): IO[Outcome] = {
    val result = if (checkOnly) {
      formatter.verdict(file).map {
        case Verdict.NeedsFormatting(formatted) => Outcome.NeedsFormatting(path, formatted)
        case Verdict.AlreadyFormatted           => Outcome.AlreadyFormatted(path)
        case Verdict.Refused(refusal)           => Outcome.Unformattable(path, refusal.reason.take(120))
      }
    } else {
      formatter.format(file).map {
        case FormatOutcome.Formatted        => Outcome.Rewritten(path)
        case FormatOutcome.AlreadyFormatted => Outcome.AlreadyFormatted(path)
        case FormatOutcome.Refused(refusal) => Outcome.Unformattable(path, refusal.reason.take(120))
      }
    }
    result.handleError(e => Outcome.Unformattable(path, Option(e.getMessage).getOrElse(e.toString)))
  }

  private def render(outcome: Outcome): String = outcome match {
    case Outcome.Unformattable(path, reason) =>
      s"ERROR: cannot format, leaving unchanged: $path ($reason)\n"
    case Outcome.NeedsFormatting(path, formatted) =>
      s"Found a not formatted file: $path .\nAfter formatting:\n$formatted\n\n"
    case Outcome.AlreadyFormatted(_) => "."
    case Outcome.Rewritten(_)        => ""
  }

  // 2, as grep and most formatters use for a usage error, keeps 1 meaning "unformatted".
  private def usage(help: Help): IO[ExitCode] =
    if (help.errors.isEmpty) IO.println(help).as(ExitCode.Success)
    else Console[IO].errorln(help).as(ExitCode(2))
}
