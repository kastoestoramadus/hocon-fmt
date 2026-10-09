package ww86.hocon_fmt

import java.nio.charset.StandardCharsets.UTF_8

import _root_.cats.data.Validated
import _root_.cats.effect.std.Console
import _root_.cats.effect.{ExitCode, IO, IOApp}
import _root_.cats.syntax.all.*
import com.monovore.decline.{Command, Help, Opts, PlatformApp}
import fs2.io.file.{Files, Path}
import ww86.hocon_fmt.interop.cats.{FileFormatter, Inspection}

/** Command line entry point: parses arguments, delegates file operations to [[FileFormatter]],
  * and prints the results. The same code runs as a native binary, a Node script and a JVM program.
  */
object CmdApi extends IOApp {

  /** `config` names a `.hocon-fmt.conf` explicitly; otherwise each file's is found from its
    * directory. `style` carries what the flags set, and wins over either.
    */
  final case class Arguments(
      files: List[Path],
      checkOnly: Boolean,
      config: Option[Path],
      style: StyleOverrides
  )

  enum Invocation derives CanEqual {
    case FileMode(arguments: Arguments)
    case Stdin(filename: String, style: StyleOverrides)
    case Version
  }

  // A boolean the user can both set and clear from the command line needs the pair: absent
  // leaves the config file in charge, and both together is a usage error.
  private def toggle(name: String, help: String): Opts[Option[Boolean]] =
    (Opts.flag(name, help).orFalse, Opts.flag(s"no-$name", s"The opposite of --$name.").orFalse).tupled.mapValidated {
      case (true, false)  => Some(true).validNel
      case (false, true)  => Some(false).validNel
      case (true, true)   => s"--$name and --no-$name cannot be combined.".invalidNel
      case (false, false) => none[Boolean].validNel
    }

  private val styleOpts: Opts[StyleOverrides] = (
    Opts
      .option[String]("separator", "The key-value separator: = (the default) or :; overrides the config file.")
      .mapValidated {
        case "="   => Separator.Equals.validNel
        case ":"   => Separator.Colon.validNel
        case other => s"--separator expects = or :, not: $other".invalidNel
      }
      .orNone,
    toggle("double-indent", "Indent the contents of nested objects four spaces; overrides the config file."),
    toggle("simplify-nested-objects", "Flatten nested objects to path keys; overrides the config file."),
    toggle(
      "fail-on-duplicates",
      "Exit 1 when a key is defined again and the earlier value never takes effect; overrides the config file."
    )
  ).mapN(StyleOverrides.apply)

  private val configOpt: Opts[Option[Path]] =
    Opts
      .option[String](
        "config",
        "Use this .hocon-fmt.conf for every file instead of the one found from each file's directory."
      )
      .map(Path(_))
      .orNone

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
        Opts.flag("version", "Print the formatter version and exit.").orFalse,
        configOpt,
        styleOpts
      ).tupled.mapValidated { case (files, checkOnly, stdin, filename, version, config, style) =>
        if (
          version && (files.nonEmpty || checkOnly || stdin || filename.nonEmpty || config.nonEmpty || style != StyleOverrides.none)
        )
          Validated.invalidNel("--version cannot be combined with formatting arguments.")
        else if (version) Validated.validNel(Invocation.Version)
        else if (stdin && (files.nonEmpty || checkOnly || config.nonEmpty))
          Validated.invalidNel("--stdin cannot be combined with files, --check or --config.")
        else if (stdin) Validated.validNel(Invocation.Stdin(filename.getOrElse("<stdin>"), style))
        else if (filename.nonEmpty) Validated.invalidNel("--stdin-filename requires --stdin.")
        else if (files.isEmpty) Validated.invalidNel("At least one file or --stdin is required.")
        else Validated.validNel(Invocation.FileMode(Arguments(files, checkOnly, config, style)))
      }
    }

  enum Result derives CanEqual {
    case Rewritten
    case AlreadyFormatted
    case NeedsFormatting(formatted: String)
    case Unformattable(reason: String)
    case Unreadable(reason: String)
    case Unwritable(reason: String)
  }

  /** One file's run: what was made of it, the findings the report made of the text it was read
    * from, and whether those findings fail the run — the file's own `.hocon-fmt.conf` may ask for
    * that, so it is a per-file decision. A finding never changes what is written.
    */
  final case class Outcome(
      result: Result,
      path: String,
      findings: List[Finding],
      failOnDuplicates: Boolean,
      reportFailure: Option[Refusal] = None
  ) {

    def ioFailed: Boolean = result match {
      case Result.Unreadable(_) | Result.Unwritable(_) => true
      case _                                           => false
    }

    def fails: Boolean = result match {
      case Result.NeedsFormatting(_)                   => true
      case Result.Unreadable(_) | Result.Unwritable(_) => false
      case _                                           => failOnDuplicates && findings.nonEmpty
    }
  }

  /** Every file's outcome, and what the process prints and exits with because of them. */
  final case class Run(outcomes: List[Outcome]) {

    // A filesystem failure is a broken run, whether reading or writing failed. A typo in a
    // CI script's path or a denied write must not pass silently. And
    // an exit status is a byte, so -1 would reach the shell as 255.
    def exitCode: ExitCode =
      if (outcomes.exists(_.ioFailed)) ExitCode(2)
      else if (outcomes.exists(_.fails)) ExitCode(1)
      else ExitCode.Success

    def rendered: String =
      (s"Running HOCON formatter for ${outcomes.size} files.\n" :: outcomes.map(render)).mkString

    // Filesystem failures are errors, so their diagnostics go to stderr like usage errors.
    def errors: String = outcomes.collect {
      case Outcome(Result.Unreadable(reason), path, _, _, _) => s"cannot read $path: $reason\n"
      case Outcome(Result.Unwritable(reason), path, _, _, _) => s"cannot write $path: $reason\n"
    }.mkString

  }

  // Scala.js hands `main` no arguments; under Node they are in `process.argv`.
  override def run(args: List[String]): IO[ExitCode] =
    command.parse(PlatformApp.ambientArgs.getOrElse(args)) match {
      case Right(Invocation.FileMode(arguments)) =>
        examineAll(arguments).flatMap {
          case Left(error) =>
            // A config nobody asked for must not decide what happens to the files, so the run
            // stops before any of them is read, the way a usage error does.
            Console[IO].errorln(error).as(ExitCode(2))
          case Right(run) =>
            (IO.print(run.rendered) *> Console[IO].error(run.errors)).as(run.exitCode)
        }
      case Right(Invocation.Version)                => IO.println(s"hocon-fmt ${BuildInfo.version}").as(ExitCode.Success)
      case Right(Invocation.Stdin(filename, style)) =>
        StdStreams.readStdin
          .map(formatStdin(_, filename, style))
          .handleError { e =>
            StdinResult("", s"cannot read $filename: ${Option(e.getMessage).getOrElse(e.toString)}\n", ExitCode(2))
          }
          .flatMap(result =>
            StdStreams.writeStdout(result.stdout) *> Console[IO].error(result.stderr).as(result.exitCode)
          )
      case Left(help) => usage(help)
    }

  final case class StdinResult(stdout: String, stderr: String, exitCode: ExitCode)

  // Stdin takes the flags but no config-file lookup: the name it is given is diagnostics only,
  // and reading a file beside it would break that promise.
  def formatStdin(content: Array[Byte], filename: String, style: StyleOverrides): StdinResult = {
    val options                                      = style.applyTo(FormatOptions.default)
    val Inspection(verdict, findings, reportFailure) = Inspection.of(content, filename, options)
    val report                                       = findings.map(findingLine(filename, _)).mkString + reportFailureLine(filename, reportFailure)
    // Only stdout carries the formatted text; the report is diagnostics, as a refusal's reason is.
    val failed = if (options.failOnDuplicates && findings.nonEmpty) ExitCode(1) else ExitCode.Success
    verdict match {
      case Verdict.NeedsFormatting(formatted) => StdinResult(formatted, report, failed)
      case Verdict.AlreadyFormatted           => StdinResult(String(content, UTF_8), report, failed)
      case Verdict.Refused(refusal)           =>
        StdinResult("", report + s"cannot format $filename: ${refusal.reason}\n", ExitCode(1))
    }
  }

  /** Every file's style: the `.hocon-fmt.conf` found from its directory, overridden by the flags;
    * an explicit `--config` takes the place of the lookup and holds for every file. Resolved
    * before any file is examined, so a bad config file stops the run before one is touched.
    */
  def styleFor(paths: List[Path], arguments: Arguments): IO[Either[String, List[(Path, FormatOptions)]]] =
    arguments.config match {
      case Some(explicit) =>
        ConfigFile.read[IO](explicit).map(_.map(base => paths.map(_ -> arguments.style.applyTo(base))))
      case None =>
        paths
          .traverse(path => ConfigFile.optionsFor[IO](path, arguments.style))
          .map(options => options.sequence.map(paths.zip(_)))
    }

  /** The whole file pipeline: canonical paths, each file's style resolved, every file examined.
    * A directory argument is walked first, for the HOCON files it holds; the arguments that could
    * not be read report alongside the files' outcomes. `Left` is a config-file error, decided
    * before any file is read.
    */
  def examineAll(arguments: Arguments): IO[Either[String, Run]] =
    formatter.distinctPaths(arguments.files).flatMap { paths =>
      Walk.expand[IO](paths).flatMap { expanded =>
        formatter.distinctPaths(expanded.files).flatMap { files =>
          styleFor(files, arguments).flatMap {
            case Left(error)   => Left(error).pure[IO]
            case Right(styled) =>
              examineAll(styled, arguments.checkOnly).map { run =>
                val unreadable = expanded.unreadable.map { case Walk.Unreadable(path, reason) =>
                  Outcome(Result.Unreadable(reason), path.toString, Nil, failOnDuplicates = false)
                }
                Right(Run(unreadable ++ run.outcomes))
              }
          }
        }
      }
    }

  /** Examines every file given, even after an unformatted one is found, and each path once.
    *
    * Exiting from inside a parallel loop used to kill the JVM mid-iteration, so `--check` could
    * miss files entirely; now nothing exits until every outcome is in. The paths arrive from
    * `distinctPaths`, so a file named twice — however spelled, or once given and once walked — is
    * not written by two fibers.
    */
  def examineAll(styled: List[(Path, FormatOptions)], checkOnly: Boolean): IO[Run] =
    styled.parTraverse { case (file, options) => examine(file, file.toString, checkOnly, options) }.map(Run(_))

  private val formatter = FileFormatter[IO]

  private def examine(file: Path, path: String, checkOnly: Boolean, options: FormatOptions): IO[Outcome] =
    formatter
      .inspect(file, options)
      .attempt
      .flatMap {
        // A read failure is not a refusal of the file's content, so it is not left to the catch
        // all below: it is the run's own error, in words to act on rather than an exception's.
        case Left(e) =>
          Outcome(Result.Unreadable(ReadFailure.message(e)), path, Nil, failOnDuplicates = false).pure[IO]
        case Right(inspection) =>
          def outcome(result: Result) =
            Outcome(result, path, inspection.findings, options.failOnDuplicates, inspection.reportFailure)
          inspection.verdict match {
            case Verdict.AlreadyFormatted           => outcome(Result.AlreadyFormatted).pure[IO]
            case Verdict.Refused(refusal)           => outcome(Result.Unformattable(refusal.reason.take(120))).pure[IO]
            case Verdict.NeedsFormatting(formatted) =>
              if (checkOnly) outcome(Result.NeedsFormatting(formatted)).pure[IO]
              else
                formatter
                  .write(file, formatted)
                  .as(outcome(Result.Rewritten))
                  .handleError(e => outcome(Result.Unwritable(ReadFailure.message(e))))
          }
      }

  private def render(outcome: Outcome): String = {
    val result = outcome.result match {
      case Result.Unreadable(_) | Result.Unwritable(_) => "" // the error line goes to stderr
      case Result.Unformattable(reason)                =>
        s"ERROR: cannot format, leaving unchanged: ${outcome.path} ($reason)\n"
      case Result.NeedsFormatting(formatted) =>
        s"Found a not formatted file: ${outcome.path} .\nAfter formatting:\n$formatted\n\n"
      case Result.AlreadyFormatted => "."
      case Result.Rewritten        => ""
    }
    result + outcome.findings.map(findingLine(outcome.path, _)).mkString + reportFailureLine(
      outcome.path,
      outcome.reportFailure
    )
  }

  private def reportFailureLine(path: String, failure: Option[Refusal]): String =
    failure.fold("")(refusal => s"WARNING: duplicate report could not run for $path: ${refusal.reason.take(120)}\n")

  private def findingLine(path: String, finding: Finding): String = finding match {
    case Finding.KeyDefinedAgain(keyPath, earlier, later) =>
      s"$path:$earlier: ${keyPath.rendered} defined again at line $later; the earlier value never takes effect\n"
  }

  // 2, as grep and most formatters use for a usage error, keeps 1 meaning "unformatted".
  private def usage(help: Help): IO[ExitCode] =
    if (help.errors.isEmpty) IO.println(help).as(ExitCode.Success)
    else Console[IO].errorln(help).as(ExitCode(2))
}
