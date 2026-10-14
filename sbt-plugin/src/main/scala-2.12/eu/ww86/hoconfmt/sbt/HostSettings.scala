package eu.ww86.hoconfmt.sbt

import sbt._
import Keys._
import lmcoursier.CoursierDependencyResolution

import HoconFormatterPlugin.{Input, examine, hoconFormatterClasspath, hoconInputs, runCheck, runFormat}
import HoconFormatterPlugin.autoImport._

/** Task plumbing for the sbt 1 host, which loads plugins with Scala 2.12: plain cached tasks and
  * the `retrieve` convenience of sbt 1's dependency resolution.
  */
object HostSettings {

  lazy val settings: Seq[Setting[_]] = Seq(
    hoconFormatSources := {
      val directories = (Compile / unmanagedResourceDirectories).value ++ (Test / unmanagedResourceDirectories).value
      directories.flatMap(directory => (directory ** ("*.conf" || "*.hocon")).get())
    },
    hoconInputs := {
      val overrides = Seq(
        hoconSeparator.value.map("separator" -> _),
        hoconDoubleIndent.value.map(value => "double-indent" -> value.toString),
        hoconSimplifyNestedObjects.value.map(value => "simplify-nested-objects" -> value.toString),
        hoconFailOnDuplicates.value.map(value => "fail-on-duplicates" -> value.toString),
        hoconFailOnRefusal.value.map(value => "fail-on-refusal" -> value.toString)
      ).flatten.toMap
      val project = thisProject.value.id
      hoconFormatSources.value.map(file => Input(file, overrides, project))
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
      runFormat(log, examined)
    },
    hoconFormatCheck := {
      val log      = streams.value.log
      val examined = examine(
        (ThisBuild / baseDirectory).value,
        hoconInputs.all(ScopeFilter(inAggregates(ThisProject))).value.flatten,
        hoconFormatterClasspath.value,
        write = false
      )
      runCheck(log, examined)
    }
  )
}
