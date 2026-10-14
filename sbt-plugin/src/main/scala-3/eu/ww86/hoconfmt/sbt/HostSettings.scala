package eu.ww86.hoconfmt.sbt

import sbt._
import Keys._
import lmcoursier.CoursierDependencyResolution
import sbt.librarymanagement.{
  ModuleDescriptorConfiguration,
  ModuleInfo,
  RichUpdateReport,
  UnresolvedWarningConfiguration,
  UpdateConfiguration
}

import HoconFormatterPlugin.{Input, examine, hoconFormatterClasspath, hoconInputs, runCheck, runFormat}
import HoconFormatterPlugin.autoImport._

/** Task plumbing for the sbt 2 host, which loads plugins with Scala 3.
  *
  * sbt 2 caches task results and refuses to cache a result holding a `File`, a path, or an
  * unserialisable case class — and even a `Unit` task that gathers `hoconInputs` across the
  * aggregation filter — so every task here is `Def.uncached`: each is consumed in the run that
  * computes it, and nothing needs a cache entry. The one `throw` fails a resolution the way
  * sbt 1's `retrieve` did.
  */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object HostSettings {

  lazy val settings = Seq(
    hoconFormatSources := Def.uncached {
      val directories = (Compile / unmanagedResourceDirectories).value ++ (Test / unmanagedResourceDirectories).value
      directories.flatMap(directory => (directory ** ("*.conf" || "*.hocon")).get())
    },
    hoconInputs := Def.uncached {
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
    hoconFormatterClasspath := Def.uncached {
      val formatter = FormatterArtifact.organization % FormatterArtifact.name % FormatterArtifact.version
      // The build's resolvers and credentials, but none of its Scala: left to itself, resolution
      // pins scala-library to the host's, a 3.8.4 library where the formatter needs 3.9.0.
      val resolution = CoursierDependencyResolution(
        csrConfiguration.value
          .withScalaVersion(Some(FormatterArtifact.scalaVersion))
          .withAutoScalaLibrary(false)
          .withForceVersions(Vector.empty)
      )
      // sbt 2 has no `retrieve`: a synthetic module carries the formatter as its only dependency,
      // and the files of the resolved report are the worker classpath.
      val descriptor = resolution.moduleDescriptor(
        ModuleDescriptorConfiguration(
          FormatterArtifact.organization % "hocon-fmt-worker" % FormatterArtifact.version,
          ModuleInfo("the isolated formatter worker")
        ).withDependencies(Vector(formatter))
      )
      resolution.update(descriptor, UpdateConfiguration(), UnresolvedWarningConfiguration(), streams.value.log) match {
        case Right(report) => new RichUpdateReport(report).allFiles.toList
        case Left(unresolved) => throw unresolved.resolveException
      }
    },
    hoconFormat := Def.uncached {
      val log = streams.value.log
      val examined = examine(
        (ThisBuild / baseDirectory).value,
        hoconInputs.all(ScopeFilter(inAggregates(ThisProject))).value.flatten,
        hoconFormatterClasspath.value,
        write = true
      )
      runFormat(log, examined)
    },
    hoconFormatCheck := Def.uncached {
      val log = streams.value.log
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
