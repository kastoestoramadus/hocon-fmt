package ww86.hocon_fmt.sbt

import sbt._
import sbt.Keys._
import lmcoursier.CoursierDependencyResolution

/** Formats the HOCON files of a build: `hoconFormat` rewrites them, `hoconFormatCheck` fails the
  * build when one is not formatted. A file the formatter refuses is reported and left alone,
  * without failing either task.
  */
object HoconFormatterPlugin extends AutoPlugin {

  override def trigger  = allRequirements
  override def requires = plugins.JvmPlugin

  object autoImport {
    val hoconSeparator             = settingKey[Option[String]]("Overrides separator (= or :).")
    val hoconDoubleIndent          = settingKey[Option[Boolean]]("Overrides double-indent.")
    val hoconSimplifyNestedObjects = settingKey[Option[Boolean]]("Overrides simplify-nested-objects.")
    val hoconFailOnDuplicates      = settingKey[Option[Boolean]]("Overrides fail-on-duplicates.")
    val hoconFormat                = taskKey[Unit]("Rewrites the HOCON files that are not formatted.")
    val hoconFormatCheck           = taskKey[Unit]("Fails if any HOCON file is not formatted, naming each one.")
    val hoconFormatSources         = taskKey[Seq[File]](
      "The HOCON files to format: *.conf and *.hocon in the Compile and Test resource directories by default."
    )
  }

  import autoImport._

  private val hoconFormatterClasspath =
    taskKey[Seq[File]]("The formatter and its dependencies, resolved apart from the build's own.")
      .withRank(KeyRanks.Invisible)

  private val hoconInputs =
    taskKey[Seq[(File, Map[String, String])]]("Sources with their project's explicit overrides.")

  final case class Examined(
      file: File,
      path: String,
      verdict: Verdict,
      warnings: Seq[String],
      failsOnDuplicates: Boolean
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    hoconSeparator             := None,
    hoconDoubleIndent          := None,
    hoconSimplifyNestedObjects := None,
    hoconFailOnDuplicates      := None,
    hoconInputs                := {
      val overrides = Seq(
        hoconSeparator.value.map("separator" -> _),
        hoconDoubleIndent.value.map(value => "double-indent" -> value.toString),
        hoconSimplifyNestedObjects.value.map(value => "simplify-nested-objects" -> value.toString),
        hoconFailOnDuplicates.value.map(value => "fail-on-duplicates" -> value.toString)
      ).flatten.toMap
      hoconFormatSources.value.map(_ -> overrides)
    },
    hoconFormatSources := {
      val directories = (Compile / unmanagedResourceDirectories).value ++ (Test / unmanagedResourceDirectories).value
      directories.flatMap(directory => (directory ** ("*.conf" || "*.hocon")).get)
    },
    hoconFormatterClasspath := {
      val formatter = FormatterArtifact.organization % FormatterArtifact.name % FormatterArtifact.version
      // The build's resolvers and credentials, but none of its Scala: left to itself, resolution
      // pins scala-library to the build's version, a 2.12 library where the formatter needs 3.x.
      val resolution = CoursierDependencyResolution(
        csrConfiguration.value
          .withScalaVersion(Some(FormatterArtifact.scalaVersion))
          .withAutoScalaLibrary(false)
          .withForceVersions(Vector.empty)
      )
      resolution
        .retrieve(formatter, None, streams.value.cacheDirectory, streams.value.log)
        .fold(unresolved => throw unresolved.resolveException, identity)
    },
    hoconFormat := {
      val log      = streams.value.log
      val examined = examine(
        (ThisBuild / baseDirectory).value,
        hoconInputs.all(ScopeFilter(inAggregates(ThisProject))).value.flatten,
        hoconFormatterClasspath.value,
        write = true
      )
      examined.foreach(e => e.warnings.foreach(warning => log.warn(s"${e.path}: $warning")))
      examined.foreach {
        case Examined(_, path, Verdict.NeedsFormatting(_), _, _) =>
          log.info(s"Formatted $path")
        case Examined(_, path, Verdict.Refused(_, reason), _, _) => log.warn(refusal(path, reason))
        case _                                                   => ()
      }
      log.info(summary(examined, needingFormatAre = "formatted"))
      failOnDuplicates(examined)
    },
    hoconFormatCheck := {
      val log      = streams.value.log
      val examined = examineAll.value
      examined.foreach(e => e.warnings.foreach(warning => log.warn(s"${e.path}: $warning")))
      examined.foreach {
        case Examined(_, path, Verdict.NeedsFormatting(_), _, _) => log.warn(s"Not formatted: $path")
        case Examined(_, path, Verdict.Refused(_, reason), _, _) => log.warn(refusal(path, reason))
        case _                                                   => ()
      }
      log.info(summary(examined, needingFormatAre = "not formatted"))
      failOnDuplicates(examined)
      val unformatted = examined.count(_.verdict.isInstanceOf[Verdict.NeedsFormatting])
      if (unformatted > 0)
        throw new MessageOnlyException(s"$unformatted HOCON files are not formatted. Run hoconFormat to fix them.")
    },
    // Each task covers the projects it aggregates itself, so a resource directory two of them
    // share is examined once rather than written by two concurrent runs.
    hoconFormat / aggregate      := false,
    hoconFormatCheck / aggregate := false
  )

  private val examineAll: Def.Initialize[Task[Seq[Examined]]] = Def.task {
    examine(
      (ThisBuild / baseDirectory).value,
      hoconInputs.all(ScopeFilter(inAggregates(ThisProject))).value.flatten,
      hoconFormatterClasspath.value,
      write = false
    )
  }

  private def examine(
      root: File,
      files: Seq[(File, Map[String, String])],
      classpath: Seq[File],
      write: Boolean
  ): Seq[Examined] =
    IsolatedFormatter.using(classpath) { formatter =>
      files
        .map { case (file, options) => file.getCanonicalFile -> options }
        .groupBy(_._1)
        .toSeq
        .sortBy(_._1)
        .map { case (file, inputs) =>
          val path   = IO.relativize(root.getCanonicalFile, file).getOrElse(file.getPath)
          val result = formatter.inspect(file.toPath, inputs.head._2, write)
          Examined(file, path, result.verdict, result.warnings, result.failsOnDuplicates)
        }
    }

  private def failOnDuplicates(examined: Seq[Examined]): Unit =
    if (examined.exists(_.failsOnDuplicates)) throw new MessageOnlyException("HOCON duplicate definitions found.")

  private def refusal(path: String, reason: String): String = s"Leaving $path unchanged: $reason"

  private def summary(examined: Seq[Examined], needingFormatAre: String): String = {
    def count(p: Verdict => Boolean) = examined.count(e => p(e.verdict))
    val needing                      = count(_.isInstanceOf[Verdict.NeedsFormatting])
    val already                      = count(_ == Verdict.AlreadyFormatted)
    val refused                      = count(_.isInstanceOf[Verdict.Refused])
    s"HOCON files: $needing $needingFormatAre, $already already formatted, $refused refused."
  }
}
