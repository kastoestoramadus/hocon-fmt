package eu.ww86.hoconfmt.mill

import mill.*
import mill.api.BuildCtx
import mill.javalib.*

/** Formats the HOCON files of a module: `hoconFormat` rewrites them, `hoconFormatCheck` fails when
  * one is not formatted. A file the formatter refuses is reported and left alone, failing
  * either command only when fail-on-refusal is enabled. Mix it into each module whose files it
  * should cover, test modules included, and run `__.hoconFormat` to cover the build.
  */
trait HoconFormatterModule extends JavaModule {
  import HoconFormatterModule.*

  /** Where the HOCON files are: a directory is searched for `*.conf` and `*.hocon`, a file is taken
    * as it is. The module's resources by default.
    */
  def hoconFormatSources: T[Seq[PathRef]] = Task { resources() }

  /** The Java API is resolved apart from the module's own runtime and Scala libraries. */
  def hoconFormatterVersion: T[String] = Task { FormatterArtifact.version }

  def hoconFormatterClasspath: T[Seq[PathRef]] = Task {
    // ScalaModule forces its own Scala version in defaultResolver; the worker owns its runtime.
    new CoursierModule.Resolver(
      repositories = repositoriesTask(),
      bind = dep => BoundDep(Lib.depToDependencyJava(dep), dep.force),
      coursierCacheCustomizer = coursierCacheCustomizer(),
      offline = Task.offline,
      checkGradleModules = checkGradleModules(),
      config = coursierConfigModule().coursierConfig()
    ).classpath(Seq(mvn"eu.ww86:hocon-fmt-java-api:${hoconFormatterVersion()}"))
  }

  /** Rewrites the HOCON files that are not formatted. */
  def hoconFormat(
      separator: Option[String] = None,
      @mainargs.arg(name = "double-indent") doubleIndent: Option[Boolean] = None,
      @mainargs.arg(name = "simplify-nested-objects") simplifyNestedObjects: Option[Boolean] = None,
      @mainargs.arg(name = "fail-on-duplicates") failOnDuplicates: Option[Boolean] = None,
      @mainargs.arg(name = "fail-on-refusal") failOnRefusal: Option[Boolean] = None
  ): Command[Unit] = Task.Command {
    val overrides = settings(separator, doubleIndent, simplifyNestedObjects, failOnDuplicates, failOnRefusal)
    val examined  = examine(hoconFormatSources(), hoconFormatterClasspath(), overrides, write = true)
    examined.foreach(e => e.warnings.foreach(warning => Task.log.warn(s"${shown(e.file)}: $warning")))
    examined.foreach {
      case Examined(file, Verdict.NeedsFormatting, _, _, _) =>
        Task.log.info(s"Formatted ${shown(file)}")
      case Examined(file, Verdict.Refused(refusal), _, _, _) => Task.log.warn(leftAlone(file, refusal))
      case Examined(_, Verdict.AlreadyFormatted, _, _, _)    => ()
    }
    Task.log.info(summary(examined, needingFormatAre = "formatted"))
    if (examined.exists(_.failsOnDuplicates)) { Task.fail("HOCON duplicate definitions found.") }
    if (examined.exists(_.failsOnRefusal)) { Task.fail("HOCON formatting refusals found.") }
  }

  /** Fails if any HOCON file is not formatted, naming each one; writes nothing. */
  def hoconFormatCheck(
      separator: Option[String] = None,
      @mainargs.arg(name = "double-indent") doubleIndent: Option[Boolean] = None,
      @mainargs.arg(name = "simplify-nested-objects") simplifyNestedObjects: Option[Boolean] = None,
      @mainargs.arg(name = "fail-on-duplicates") failOnDuplicates: Option[Boolean] = None,
      @mainargs.arg(name = "fail-on-refusal") failOnRefusal: Option[Boolean] = None
  ): Command[Unit] = Task.Command {
    val overrides = settings(separator, doubleIndent, simplifyNestedObjects, failOnDuplicates, failOnRefusal)
    val examined  = examine(hoconFormatSources(), hoconFormatterClasspath(), overrides, write = false)
    examined.foreach(e => e.warnings.foreach(warning => Task.log.warn(s"${shown(e.file)}: $warning")))
    examined.foreach {
      case Examined(file, Verdict.NeedsFormatting, _, _, _)  => Task.log.warn(s"Not formatted: ${shown(file)}")
      case Examined(file, Verdict.Refused(refusal), _, _, _) => Task.log.warn(leftAlone(file, refusal))
      case Examined(_, Verdict.AlreadyFormatted, _, _, _)    => ()
    }
    Task.log.info(summary(examined, needingFormatAre = "not formatted"))
    if (examined.exists(_.failsOnDuplicates)) { Task.fail("HOCON duplicate definitions found.") }
    if (examined.exists(_.failsOnRefusal)) { Task.fail("HOCON formatting refusals found.") }
    val unformatted = examined.count(_.needsFormatting)
    if (unformatted > 0) { Task.fail(s"$unformatted HOCON files are not formatted. Run hoconFormat to fix them.") }
  }
}

object HoconFormatterModule {

  final private case class Examined(
      file: os.Path,
      verdict: Verdict,
      warnings: Seq[String],
      failsOnDuplicates: Boolean,
      failsOnRefusal: Boolean
  ) {
    def needsFormatting: Boolean = verdict == Verdict.NeedsFormatting
  }

  private def settings(
      separator: Option[String],
      doubleIndent: Option[Boolean],
      simplifyNestedObjects: Option[Boolean],
      failOnDuplicates: Option[Boolean],
      failOnRefusal: Option[Boolean]
  ): Map[String, String] =
    Seq(
      separator.map("separator" -> _),
      doubleIndent.map(value => "double-indent" -> value.toString),
      simplifyNestedObjects.map(value => "simplify-nested-objects" -> value.toString),
      failOnDuplicates.map(value => "fail-on-duplicates" -> value.toString),
      failOnRefusal.map(value => "fail-on-refusal" -> value.toString)
    ).flatten.toMap

  private def examine(
      sources: Seq[PathRef],
      classpath: Seq[PathRef],
      overrides: Map[String, String],
      write: Boolean
  ): Seq[Examined] =
    IsolatedFormatter.using(classpath.map(_.path.toNIO.toUri.toURL)) { formatter =>
      hoconFiles(sources).map { file =>
        val result = formatter.inspect(file.toNIO, overrides, write)
        Examined(file, result.verdict, result.warnings, result.failsOnDuplicates, result.failsOnRefusal)
      }
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

  private def leftAlone(file: os.Path, reason: String): String =
    s"Leaving ${shown(file)} unchanged: $reason"

  private def summary(examined: Seq[Examined], needingFormatAre: String): String = {
    val needing = examined.count(_.needsFormatting)
    val already = examined.count(_.verdict == Verdict.AlreadyFormatted)
    val refused = examined.count(_.verdict.isInstanceOf[Verdict.Refused])
    s"HOCON files: $needing $needingFormatAre, $already already formatted, $refused refused."
  }
}
