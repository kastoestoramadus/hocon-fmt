package eu.ww86.hoconfmt.mill

import mill.*
import mill.api.BuildCtx
import mill.javalib.JavaModule
import eu.ww86.hoconfmt.{ConfigLookup, DuplicateReport, Finding, FormatOptions, Refusal, Separator, StyleOverrides, Verdict}

/** Formats the HOCON files of a module: `hoconFormat` rewrites them, `hoconFormatCheck` fails when
  * one is not formatted. A file the formatter refuses is reported and left alone, without failing
  * either command. Mix it into each module whose files it should cover, test modules included, and
  * run `__.hoconFormat` to cover the build.
  */
trait HoconFormatterModule extends JavaModule {
  import HoconFormatterModule.*

  /** Where the HOCON files are: a directory is searched for `*.conf` and `*.hocon`, a file is taken
    * as it is. The module's resources by default.
    */
  def hoconFormatSources: T[Seq[PathRef]] = Task { resources() }

  /** Rewrites the HOCON files that are not formatted. */
  def hoconFormat(separator: Option[String] = None,
      @mainargs.arg(name = "double-indent") doubleIndent: Option[Boolean] = None,
      @mainargs.arg(name = "simplify-nested-objects") simplifyNestedObjects: Option[Boolean] = None,
      @mainargs.arg(name = "fail-on-duplicates") failOnDuplicates: Option[Boolean] = None): Command[Unit] = Task.Command {
    val overrides = StyleOverrides(separator.map {
      case "=" => Separator.Equals
      case ":" => Separator.Colon
      case other => throw new IllegalArgumentException(s"separator: expected = or :, got $other")
    }, doubleIndent, simplifyNestedObjects, failOnDuplicates)
    val examined = examine(hoconFormatSources(), overrides)
    examined.foreach(e => e.warnings.foreach(warning => Task.log.warn(s"${shown(e.file)}: $warning")))
    examined.foreach {
      case Examined(file, Verdict.NeedsFormatting(formatted), _, _) =>
        os.write.over(file, formatted)
        Task.log.info(s"Formatted ${shown(file)}")
      case Examined(file, Verdict.Refused(refusal), _, _) => Task.log.warn(leftAlone(file, refusal))
      case Examined(_, Verdict.AlreadyFormatted, _, _)    => ()
    }
    Task.log.info(summary(examined, needingFormatAre = "formatted"))
    if (examined.exists(_.failsOnDuplicates)) { Task.fail("HOCON duplicate definitions found.") }
  }

  /** Fails if any HOCON file is not formatted, naming each one; writes nothing. */
  def hoconFormatCheck(separator: Option[String] = None,
      @mainargs.arg(name = "double-indent") doubleIndent: Option[Boolean] = None,
      @mainargs.arg(name = "simplify-nested-objects") simplifyNestedObjects: Option[Boolean] = None,
      @mainargs.arg(name = "fail-on-duplicates") failOnDuplicates: Option[Boolean] = None): Command[Unit] = Task.Command {
    val overrides = StyleOverrides(separator.map {
      case "=" => Separator.Equals
      case ":" => Separator.Colon
      case other => throw new IllegalArgumentException(s"separator: expected = or :, got $other")
    }, doubleIndent, simplifyNestedObjects, failOnDuplicates)
    val examined = examine(hoconFormatSources(), overrides)
    examined.foreach(e => e.warnings.foreach(warning => Task.log.warn(s"${shown(e.file)}: $warning")))
    examined.foreach {
      case Examined(file, Verdict.NeedsFormatting(_), _, _) => Task.log.warn(s"Not formatted: ${shown(file)}")
      case Examined(file, Verdict.Refused(refusal), _, _)   => Task.log.warn(leftAlone(file, refusal))
      case Examined(_, Verdict.AlreadyFormatted, _, _)      => ()
    }
    Task.log.info(summary(examined, needingFormatAre = "not formatted"))
    if (examined.exists(_.failsOnDuplicates)) { Task.fail("HOCON duplicate definitions found.") }
    val unformatted = examined.count(_.needsFormatting)
    if (unformatted > 0) { Task.fail(s"$unformatted HOCON files are not formatted. Run hoconFormat to fix them.") }
  }
}

object HoconFormatterModule {

  final private case class Examined(file: os.Path, verdict: Verdict, warnings: Seq[String], failsOnDuplicates: Boolean) {
    def needsFormatting: Boolean = verdict.isInstanceOf[Verdict.NeedsFormatting]
  }

  private def optionsFor(file: os.Path, overrides: StyleOverrides): FormatOptions = {
    def walk(dir: os.Path): FormatOptions = {
      ConfigLookup.decide(os.exists(dir / FormatOptions.ConfigFileName), os.exists(dir / ".git")) match {
        case ConfigLookup.Decision.Found =>
          val config = dir / FormatOptions.ConfigFileName
          val text = scala.util.Try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
              .decode(java.nio.ByteBuffer.wrap(os.read.bytes(config))).toString
          }.fold(error => throw new IllegalArgumentException(s"cannot read $config: ${error.getMessage}", error), identity)
          FormatOptions.parse(text, config.toString).fold(reason => throw new IllegalArgumentException(reason), identity)
        case ConfigLookup.Decision.Stop => FormatOptions.default
        case ConfigLookup.Decision.Parent => if (dir == os.root) { FormatOptions.default } else { walk(dir / os.up) }
      }
    }
    overrides.applyTo(walk(file / os.up))
  }

  private def examine(sources: Seq[PathRef], overrides: StyleOverrides): Seq[Examined] =
    hoconFiles(sources).map { file =>
      val options = optionsFor(file, overrides)
      val content = os.read.bytes(file)
      val verdict = Verdict.of(content, shown(file), options)
      val report = verdict match {
        case Verdict.Refused(Refusal.NotUtf8) | Verdict.Refused(Refusal.OtherFormat(_)) => Right(Nil)
        case _ => DuplicateReport.findings(new String(content, java.nio.charset.StandardCharsets.UTF_8), shown(file))
      }
      val warnings = report.fold(reason => Seq(s"duplicate report could not run: ${reason.reason}"), _.map {
        case Finding.KeyDefinedAgain(path, earlier, later) =>
          s"${path.rendered} defined again on line $later; definition on line $earlier never takes effect"
      })
      Examined(file, verdict, warnings, options.failOnDuplicates && report.exists(_.nonEmpty))
    }

  private def hoconFiles(sources: Seq[PathRef]): Seq[os.Path] =
    sources
      .map(_.path)
      .filter(os.exists)
      .flatMap(path => if os.isDir(path) then os.walk(path).filter(isHocon) else Seq(path))
      .distinct
      .sorted

  private def isHocon(path: os.Path): Boolean =
    os.isFile(path) && (path.ext == "conf" || path.ext == "hocon")

  private def shown(file: os.Path): String =
    if file.startsWith(BuildCtx.workspaceRoot) then file.relativeTo(BuildCtx.workspaceRoot).toString
    else file.toString

  private def leftAlone(file: os.Path, refusal: Refusal): String =
    s"Leaving ${shown(file)} unchanged: ${refusal.reason}"

  private def summary(examined: Seq[Examined], needingFormatAre: String): String = {
    val needing = examined.count(_.needsFormatting)
    val already = examined.count(_.verdict == Verdict.AlreadyFormatted)
    val refused = examined.count(_.verdict.isInstanceOf[Verdict.Refused])
    s"HOCON files: $needing $needingFormatAre, $already already formatted, $refused refused."
  }
}
