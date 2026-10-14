package eu.ww86.hoconfmt.sbt

import sbt._
import sbt.Keys._

/** Formats the HOCON files of a build: `hoconFormat` rewrites them, `hoconFormatCheck` fails the
  * build when one is not formatted. A file the formatter refuses is reported and left alone,
  * failing either task only when fail-on-refusal is enabled.
  *
  * The sources cross-build the sbt 1.x (Scala 2.12) and sbt 2 (Scala 3) hosts; the two differ
  * only in how a task is declared and how the formatter's dependencies are resolved, which is
  * the `HostSettings` object picked per host Scala version from `scala-2.12` and `scala-3`.
  */
object HoconFormatterPlugin extends AutoPlugin {

  override def trigger  = allRequirements
  override def requires = plugins.JvmPlugin

  object autoImport {
    val hoconSeparator             = settingKey[Option[String]]("Overrides separator (= or :).")
    val hoconDoubleIndent          = settingKey[Option[Boolean]]("Overrides double-indent.")
    val hoconSimplifyNestedObjects = settingKey[Option[Boolean]]("Overrides simplify-nested-objects.")
    val hoconFailOnRefusal         = settingKey[Option[Boolean]]("Overrides fail-on-refusal.")
    val hoconFailOnDuplicates      = settingKey[Option[Boolean]]("Overrides fail-on-duplicates.")
    val hoconFormat                = taskKey[Unit]("Rewrites the HOCON files that are not formatted.")
    val hoconFormatCheck           = taskKey[Unit]("Fails if any HOCON file is not formatted, naming each one.")
    val hoconFormatSources         = taskKey[Seq[File]](
      "The HOCON files to format: *.conf and *.hocon in the Compile and Test resource directories by default."
    )
  }

  import autoImport._

  val hoconFormatterClasspath =
    taskKey[Seq[File]]("The formatter and its dependencies, resolved apart from the build's own.")
      .withRank(KeyRanks.Invisible)

  val hoconInputs = taskKey[Seq[Input]]("Sources with the project's explicit overrides.")

  /** One source file with the settings of the project that lists it: a file two aggregated
    * projects both list carries their settings so a conflict can be named rather than dropped.
    */
  final case class Input(file: File, options: Map[String, String], project: String)

  final case class Examined(
      file: File,
      path: String,
      verdict: Verdict,
      warnings: Seq[String],
      failsOnDuplicates: Boolean,
      failsOnRefusal: Boolean
  )

  override def projectSettings = HostSettings.settings ++ Seq(
    hoconSeparator             := None,
    hoconDoubleIndent          := None,
    hoconSimplifyNestedObjects := None,
    hoconFailOnDuplicates      := None,
    hoconFailOnRefusal         := None,
    // Each task covers the projects it aggregates itself, so a resource directory two of them
    // share is examined once rather than written by two concurrent runs.
    hoconFormat / aggregate      := false,
    hoconFormatCheck / aggregate := false
  )

  /** The hoconFormat task body once the inputs and the worker classpath are gathered. */
  def runFormat(log: Logger, examined: Seq[Examined]): Unit = {
    examined.foreach(e => e.warnings.foreach(warning => log.warn(s"${e.path}: $warning")))
    examined.foreach {
      case Examined(_, path, Verdict.NeedsFormatting(_), _, _, _) =>
        log.info(s"Formatted $path")
      case Examined(_, path, Verdict.Refused(_, reason), _, _, _) => log.warn(refusal(path, reason))
      case _                                                      => ()
    }
    log.info(summary(examined, needingFormatAre = "formatted"))
    failOnDuplicates(examined)
    failOnRefusal(examined)
  }

  /** The hoconFormatCheck task body once the inputs and the worker classpath are gathered.
    *
    * A task failure is the only way a build-tool plugin fails a build, so the checks below end
    * in exceptions naming what to do; the same holds for the private failOn helpers.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def runCheck(log: Logger, examined: Seq[Examined]): Unit = {
    examined.foreach(e => e.warnings.foreach(warning => log.warn(s"${e.path}: $warning")))
    examined.foreach {
      case Examined(_, path, Verdict.NeedsFormatting(_), _, _, _) => log.warn(s"Not formatted: $path")
      case Examined(_, path, Verdict.Refused(_, reason), _, _, _) => log.warn(refusal(path, reason))
      case _                                                      => ()
    }
    log.info(summary(examined, needingFormatAre = "not formatted"))
    failOnDuplicates(examined)
    failOnRefusal(examined)
    val unformatted = examined.count(e => needsFormatting(e.verdict))
    // A task failure is the only way a build-tool plugin fails a build, and the message names
    // the command that fixes it.
    if (unformatted > 0)
      throw new MessageOnlyException(s"$unformatted HOCON files are not formatted. Run hoconFormat to fix them.")
  }

  private def needsFormatting(verdict: Verdict): Boolean =
    verdict match {
      case _: Verdict.NeedsFormatting => true
      case _                          => false
    }

  private def isRefused(verdict: Verdict): Boolean =
    verdict match {
      case _: Verdict.Refused => true
      case _                  => false
    }

  def examine(
      root: File,
      inputs: Seq[Input],
      classpath: Seq[File],
      write: Boolean
  ): Seq[Examined] =
    IsolatedFormatter.using(classpath) { formatter =>
      inputs
        .map(input => input.file.getAbsoluteFile -> input)
        .groupBy(_._1.getCanonicalFile)
        .toSeq
        .sortBy(_._1)
        .map { case (_, grouped) =>
          // groupBy makes every group non-empty; apply keeps that promise without the partial
          // `head`.
          val input = grouped(0)._2
          val path  = IO.relativize(root.getCanonicalFile, input.file).getOrElse(input.file.getPath)
          refuseConflict(path, grouped.map(_._2))
          val result = formatter.inspect(input.file.toPath, input.options, write)
          Examined(input.file, path, result.verdict, result.warnings, result.failsOnDuplicates, result.failsOnRefusal)
        }
    }

  /** A file two aggregated projects both list is examined once, but their explicit settings must
    * agree: silently formatting it with whichever project came first would depend on the order
    * of the aggregation filter and could leave the other project's check failing.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def refuseConflict(path: String, inputs: Seq[Input]): Unit = {
    val owners = inputs.map(input => (input.project, input.options)).distinct.sortBy(_._1)
    if (owners.map(_._2).distinct.size > 1) {
      val names =
        if (owners.size == 2) owners.map(_._1).mkString(" and ") else owners.map(_._1).mkString(", ")
      val details = owners
        .map { case (project, options) =>
          val settings =
            if (options.isEmpty) "nothing"
            else options.toSeq.sortBy(_._1).map { case (key, value) => s"$key = $value" }.mkString(", ")
          s"$project sets $settings"
        }
        .mkString(", ")
      throw new MessageOnlyException(
        s"$path: projects $names share this file but set different HOCON options ($details); " +
          "give them the same options or move the file"
      )
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def failOnDuplicates(examined: Seq[Examined]): Unit =
    if (examined.exists(_.failsOnDuplicates)) throw new MessageOnlyException("HOCON duplicate definitions found.")

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def failOnRefusal(examined: Seq[Examined]): Unit =
    if (examined.exists(_.failsOnRefusal)) throw new MessageOnlyException("HOCON formatting refusals found.")

  private def refusal(path: String, reason: String): String = s"Leaving $path unchanged: $reason"

  private def summary(examined: Seq[Examined], needingFormatAre: String): String = {
    def count(p: Verdict => Boolean) = examined.count(e => p(e.verdict))
    val needing                      = count(needsFormatting)
    val already                      = count(_ == Verdict.AlreadyFormatted)
    val refused                      = count(isRefused)
    s"HOCON files: $needing $needingFormatAre, $already already formatted, $refused refused."
  }
}
