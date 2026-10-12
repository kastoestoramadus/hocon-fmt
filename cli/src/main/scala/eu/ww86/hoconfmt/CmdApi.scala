package eu.ww86.hoconfmt

import java.nio.charset.StandardCharsets.UTF_8

import _root_.cats.data.Validated
import _root_.cats.effect.std.Console
import _root_.cats.effect.{ExitCode, IO, IOApp}
import _root_.cats.syntax.all.*
import com.monovore.decline.{Command, Help, Opts, PlatformApp}
import fs2.io.file.{Files, Path}
import fs2.Stream
import eu.ww86.hoconfmt.interop.cats.{FileFormatter, Inspection}

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
      style: StyleOverrides,
      report: Option[ReportTarget] = None
  )

  enum ReportTarget derives CanEqual {
    case Stdout
    case File(path: Path)
  }

  enum Invocation derives CanEqual {
    case FileMode(arguments: Arguments)
    case Stdin(filename: String, style: StyleOverrides, report: Option[ReportTarget] = None)
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
    ),
    toggle("fail-on-refusal", "Exit 1 when a selected file cannot be formatted; overrides the config file.")
  ).mapN(StyleOverrides.apply)

  private val configOpt: Opts[Option[Path]] =
    Opts
      .option[String](
        "config",
        "Use this .hocon-fmt.conf for every file instead of the one found from each file's directory."
      )
      .map(Path(_))
      .orNone

  private val reportOpt: Opts[Option[ReportTarget]] =
    Opts
      .option[String]("report", "Write JSON to a path or - for stdout; formatting and exit policies are unchanged.")
      .mapValidated {
        case ""   => "--report requires a nonempty path or -.".invalidNel
        case "-"  => ReportTarget.Stdout.validNel
        case path => ReportTarget.File(Path(path)).validNel
      }
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
        styleOpts,
        reportOpt
      ).tupled.mapValidated { case (files, checkOnly, stdin, filename, version, config, style, report) =>
        if (
          version && (files.nonEmpty || checkOnly || stdin || filename.nonEmpty || config.nonEmpty || report.nonEmpty || style != StyleOverrides.none)
        )
          Validated.invalidNel("--version cannot be combined with formatting arguments.")
        else if (version) Validated.validNel(Invocation.Version)
        else if (stdin && (files.nonEmpty || checkOnly || config.nonEmpty))
          Validated.invalidNel("--stdin cannot be combined with files, --check or --config.")
        else if (stdin && report.contains(ReportTarget.Stdout))
          Validated.invalidNel("--stdin cannot be combined with --report -; use a report file.")
        else if (stdin) Validated.validNel(Invocation.Stdin(filename.getOrElse("<stdin>"), style, report))
        else if (filename.nonEmpty) Validated.invalidNel("--stdin-filename requires --stdin.")
        else if (files.isEmpty) Validated.invalidNel("At least one file or --stdin is required.")
        else Validated.validNel(Invocation.FileMode(Arguments(files, checkOnly, config, style, report)))
      }
    }

  enum Result derives CanEqual {
    case Rewritten
    case AlreadyFormatted
    case NeedsFormatting(formatted: String)
    case Unformattable(refusal: Refusal)
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
      reportFailure: Option[Refusal] = None,
      failOnRefusal: Boolean = false
  ) {

    def ioFailed: Boolean = result match {
      case Result.Unreadable(_) | Result.Unwritable(_) => true
      case _                                           => false
    }

    def fails: Boolean = result match {
      case Result.NeedsFormatting(_)                   => true
      case Result.Unreadable(_) | Result.Unwritable(_) => false
      case Result.Unformattable(_)                     => failOnRefusal || (failOnDuplicates && findings.nonEmpty)
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

    def rendered: String = {
      val skipped = outcomes.count(_.result match {
        case Result.Unformattable(_) => true
        case _                       => false
      })
      (s"Running HOCON formatter for ${outcomes.size} files.\n" :: outcomes.map(render)).mkString +
        (if (skipped > 0) s"\nHOCON files: $skipped skipped (refused).\n" else "")
    }

    // Filesystem failures are errors, so their diagnostics go to stderr like usage errors.
    def errors: String = outcomes.collect {
      case Outcome(Result.Unreadable(reason), path, _, _, _, _) => s"cannot read $path: $reason\n"
      case Outcome(Result.Unwritable(reason), path, _, _, _, _) => s"cannot write $path: $reason\n"
    }.mkString

  }

  // Scala.js hands `main` no arguments; under Node they are in `process.argv`.
  override def run(args: List[String]): IO[ExitCode] =
    command.parse(PlatformApp.ambientArgs.getOrElse(args)) match {
      case Right(Invocation.FileMode(arguments)) =>
        examineSelected(arguments).flatMap { selected =>
          val (human, code, report) = selected.result match {
            case Left(error) =>
              val report = () => {
                val configReport = JsonReport.configurationFailures(selected.checked, selected.configFailures)
                val failed       = configReport.failed ++ JsonReport.of(Run(selected.unavailable)).failed
                configReport.copy(failed = failed)
              }
              (Console[IO].errorln(error.message), ExitCode(2), report)
            case Right(run) =>
              val output = arguments.report match {
                case Some(ReportTarget.Stdout) => Console[IO].error(run.rendered)
                case _                         => IO.print(run.rendered)
              }
              (output *> Console[IO].error(run.errors), run.exitCode, () => JsonReport.of(run))
          }
          (human *> emitReport(arguments.report, report(), selected.protectedPaths)).as(code)
        }
      case Right(Invocation.Version)                             => IO.println(s"hocon-fmt ${BuildInfo.version}").as(ExitCode.Success)
      case Right(Invocation.Stdin(filename, style, destination)) =>
        StdStreams.readStdin
          .map(formatStdin(_, filename, style, includeReport = destination.nonEmpty))
          .handleError { e =>
            val message = s"cannot read $filename: ${Option(e.getMessage).getOrElse(e.toString)}"
            val report  = JsonReport.of(Run(List(Outcome(Result.Unreadable(message), filename, Nil, false))))
            StdinResult("", s"$message\n", ExitCode(2), Some(report))
          }
          .flatMap(result =>
            (StdStreams.writeStdout(result.stdout) *> Console[IO].error(result.stderr) *>
              result.report.fold(IO.unit)(emitReport(destination, _, Nil))).as(result.exitCode)
          )
      case Left(help) => usage(help)
    }

  final case class StdinResult(stdout: String, stderr: String, exitCode: ExitCode, report: Option[JsonReport] = None)

  // Stdin takes the flags but no config-file lookup: the name it is given is diagnostics only,
  // and reading a file beside it would break that promise.
  def formatStdin(
      content: Array[Byte],
      filename: String,
      style: StyleOverrides,
      includeReport: Boolean = false
  ): StdinResult = {
    val options                                      = style.applyTo(FormatOptions.default)
    val Inspection(verdict, findings, reportFailure) = Inspection.of(content, filename, options)
    val report                                       = findings.map(findingLine(filename, _)).mkString + reportFailureLine(filename, reportFailure)
    // Only stdout carries the formatted text; the report is diagnostics, as a refusal's reason is.
    val failed = if (options.failOnDuplicates && findings.nonEmpty) ExitCode(1) else ExitCode.Success
    val result = verdict match {
      case Verdict.NeedsFormatting(formatted) => StdinResult(formatted, report, failed)
      case Verdict.AlreadyFormatted           => StdinResult(String(content, UTF_8), report, failed)
      case Verdict.Refused(refusal)           =>
        StdinResult("", report + s"cannot format $filename: ${refusal.reason}\n${advice(refusal)}", ExitCode(1))
    }
    if (includeReport) {
      val decision = verdict match {
        case Verdict.NeedsFormatting(_) => Result.Rewritten
        case Verdict.AlreadyFormatted   => Result.AlreadyFormatted
        case Verdict.Refused(refusal)   => Result.Unformattable(refusal)
      }
      result.copy(report =
        Some(
          JsonReport.of(
            Run(
              List(
                Outcome(decision, filename, findings, options.failOnDuplicates, reportFailure, options.failOnRefusal)
              )
            )
          )
        )
      )
    } else result
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
  final case class Selected(
      checked: Int,
      result: Either[ConfigFile.Failure, Run],
      protectedPaths: List[Path],
      configFailures: List[ConfigFile.Failure],
      unavailable: List[Outcome]
  )

  def examineAll(arguments: Arguments): IO[Either[String, Run]] =
    examineSelected(arguments).map(_.result.left.map(_.message))

  def examineSelected(arguments: Arguments): IO[Selected] =
    formatter.distinctPaths(arguments.files).flatMap { paths =>
      Walk.expand[IO](paths).flatMap { expanded =>
        formatter.distinctPaths(expanded.files).flatMap { files =>
          val unreadable = expanded.unreadable.map { case Walk.Unreadable(path, reason) =>
            Outcome(Result.Unreadable(reason), path.toString, Nil, failOnDuplicates = false)
          }
          val options: IO[Either[(ConfigFile.Failure, List[ConfigFile.Failure]), List[FormatOptions]]] =
            arguments.config match {
              case Some(explicit) =>
                ConfigFile
                  .readDetailed[IO](explicit)
                  .map(_.map(base => files.map(_ => base)).left.map(error => (error, List(error))))
              case None =>
                files.traverse(ConfigFile.optionsForDetailed[IO](_, StyleOverrides.none)).map { resolved =>
                  val failures = resolved.collect { case Left(error) => error }.distinct
                  resolved.sequence.left.map(error => (error, failures))
                }
            }
          options.flatMap { resolved =>
            val styled         = resolved.map(values => files.zip(values.map(arguments.style.applyTo)))
            val checked        = files.size + unreadable.size
            val protectedPaths = files ++ arguments.config.toList
            styled match {
              case Left((error, failures)) =>
                Selected(checked, Left(error), protectedPaths, failures, unreadable).pure[IO]
              case Right(values) =>
                examineAll(values, arguments.checkOnly).map { run =>
                  Selected(checked, Right(Run(unreadable ++ run.outcomes)), protectedPaths, Nil, unreadable)
                }
            }
          }
        }
      }
    }

  def writeReport(path: Path, report: JsonReport, protectedPaths: List[Path]): IO[Either[String, Unit]] = {
    def canonical(file: Path): IO[Path] = Files[IO].realPath(file).handleError(_ => file.absolute)
    val configs                         = protectedPaths.traverse(ConfigFile.discover[IO]).map(_.flatten)
    configs
      .flatMap { discovered =>
        (canonical(path), (protectedPaths ++ discovered).traverse(canonical)).tupled.flatMap {
          case (target, protectedFiles) =>
            if (protectedFiles.contains(target) || target.fileName.toString == FormatOptions.ConfigFileName)
              Left("report destination is a selected input or configuration file").pure[IO]
            else
              Stream
                .emit(report.rendered)
                .through(Files[IO].writeUtf8(path))
                .compile
                .drain
                .attempt
                .map(_.left.map(ReadFailure.message))
        }
      }
      .handleError(error => Left(ReadFailure.message(error)))
  }

  private def emitReport(
      destination: Option[ReportTarget],
      report: => JsonReport,
      protectedPaths: List[Path]
  ): IO[Unit] =
    destination match {
      case None                      => IO.unit
      case Some(ReportTarget.Stdout) =>
        StdStreams.writeStdout(report.rendered).attempt.flatMap {
          case Left(error) => Console[IO].errorln(s"cannot write report -: ${ReadFailure.message(error)}")
          case Right(_)    => IO.unit
        }
      case Some(ReportTarget.File(path)) =>
        writeReport(path, report, protectedPaths).flatMap {
          case Left(error) => Console[IO].errorln(s"cannot write report $path: $error")
          case Right(_)    => IO.unit
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
            Outcome(
              result,
              path,
              inspection.findings,
              options.failOnDuplicates,
              inspection.reportFailure,
              options.failOnRefusal
            )
          inspection.verdict match {
            case Verdict.AlreadyFormatted           => outcome(Result.AlreadyFormatted).pure[IO]
            case Verdict.Refused(refusal)           => outcome(Result.Unformattable(refusal)).pure[IO]
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
      case Result.Unformattable(refusal)               =>
        s"ERROR: cannot format, leaving unchanged: ${outcome.path} (${refusal.reason.take(120)})${advice(refusal)}\n"
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

  /** What to do about a refusal by hand, where one sentence of reason is not enough to act on. The
    * shadowed definition has no automatic fix — the included file cannot be read here — so the
    * ways out are the ones the user can weigh against what they meant.
    */
  private def advice(refusal: Refusal): String = refusal match {
    case Refusal.ShadowedByInclude(_, _, definitionLine) =>
      s"\n  fix by hand: delete line $definitionLine if the included values may reach the key, move it or the include, or write the value as one definition\n"
    case _ => ""
  }

  private def findingLine(path: String, finding: Finding): String = finding match {
    case Finding.KeyDefinedAgain(keyPath, earlier, later) =>
      s"$path:$earlier: ${keyPath.rendered} defined again at line $later; the earlier value never takes effect\n"
  }

  // 2, as grep and most formatters use for a usage error, keeps 1 meaning "unformatted".
  private def usage(help: Help): IO[ExitCode] =
    if (help.errors.isEmpty) IO.println(help).as(ExitCode.Success)
    else Console[IO].errorln(help).as(ExitCode(2))
}
