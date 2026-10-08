import scala.scalanative.build.{LTO, Mode}

val scala3    = "3.8.2"
val sconfig   = "1.12.4"
val munit     = "1.2.4"
val osLib     = "0.11.8"
val sjavatime = "1.5.0"
// The latest stable Laminar for _sjs1_3; 18.0.0-M5 is a milestone.
val laminar = "17.2.1"

val zioVersion      = "2.1.26"
val catsEffect      = "3.7.1"
val fs2             = "3.14.0"
val decline         = "2.6.2"
val munitCatsEffect = "2.2.1"
val munitScalaCheck = "1.2.0"

ThisBuild / scalaVersion := scala3
ThisBuild / version      := "0.1.0-SNAPSHOT"

// The compiler enforces what review would otherwise have to catch: a match that misses a case (a
// new Refusal reaching code that does not handle it), a value computed and dropped, == between
// types that can never be equal, a null from a Java API used as a value, an object read before it
// is initialised. Every warning fails the build. The sbt plugin is Scala 2.12 and sets its own.
ThisBuild / scalacOptions ++= {
  if (scalaBinaryVersion.value == "3")
    Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Werror",
      "-Wunused:all",
      "-Wvalue-discard",
      "-Wnonunit-statement",
      "-Wsafe-init",
      "-Wimplausible-patterns",
      "-Wshadow:all",
      "-Winfer-union",
      "-Wrecurse-with-default",
      "-Wenum-comment-discard",
      "-Wwrong-arrow",
      "-language:strictEquality",
      "-Yexplicit-nulls"
    )
  else Nil
}

// Coordinates and the metadata Sonatype requires before anything can reach Maven Central,
// which is what `cs` and therefore the pre-commit coursier hook resolve from.
ThisBuild / organization     := "eu.ww86"
ThisBuild / organizationName := "kastoestoramadus"
ThisBuild / homepage         := Some(url("https://github.com/kastoestoramadus/hocon-fmt"))
ThisBuild / licenses         := Seq("GPL-3.0" -> url("https://www.gnu.org/licenses/gpl-3.0.html"))
ThisBuild / scmInfo          := Some(
  ScmInfo(
    url("https://github.com/kastoestoramadus/hocon-fmt"),
    "scm:git:https://github.com/kastoestoramadus/hocon-fmt.git"
  )
)
ThisBuild / developers := List(
  Developer(
    "kastoestoramadus",
    "Waldemar Wosinski",
    "",
    url("https://github.com/kastoestoramadus")
  )
)

// Releases are staged locally and uploaded to the Central Portal bundle by `sonaUpload`; snapshots
// go to the portal's snapshot repository. sbt reads SONATYPE_USERNAME and SONATYPE_PASSWORD itself.
ThisBuild / publishTo := {
  if (isSnapshot.value) Some("central-snapshots" at "https://central.sonatype.com/repository/maven-snapshots/")
  else localStaging.value
}

/** sbt prints one unlabelled "Passed: Total N" per aggregated project, and the Scala.js block
  * arrives without the `[info]` prefix, so nothing says which runtime a result came from. The
  * banner goes in a Cleanup hook rather than Setup so it lands next to that project's summary
  * instead of with everything else at start-up.
  */
def announceRuntime(label: String): Setting[?] =
  Test / testOptions += Tests.Cleanup(() => println(s"========== $label =========="))

lazy val root = project
  .in(file("."))
  // Aggregation drives compile, scalafmt and the rest.
  .aggregate(
    zioJVM,
    zioJS,
    zioNative,
    coreJVM,
    coreJS,
    coreNative,
    cliJVM,
    cliJS,
    cliNative,
    acceptance,
    web,
    site,
    benchJVM,
    benchJS,
    benchNative,
    sbtPlugin
  )
  .settings(
    name           := "hocon-fmt",
    publish / skip := true,
    // `test` is spelled out instead of aggregated: aggregated projects run concurrently, and
    // their summaries then arrive unlabelled and interleaved, with no way to tell which runtime
    // produced which. Running them in order keeps each banner next to its own result.
    Test / test / aggregate := false,
    Test / test             := Def
      .sequential(
        coreJVM / Test / test,
        cliJVM / Test / test,
        zioJVM / Test / test,
        zioJS / Test / test,
        zioNative / Test / test,
        coreJS / Test / test,
        cliJS / Test / test,
        web / Test / test,
        site / Test / test,
        coreNative / Test / test,
        cliNative / Test / test
      )
      .value
  )

/** Formatting proper: a pure `String => Either[Refusal, String]` with no file access and no
  * effects, which is what lets it cross-build. It depends on nothing but sconfig because the
  * build-tool plugins load it into their hosts' class loaders.
  */
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(
    name := "hocon-fmt-core",
    Test / sourceGenerators += Def.task {
      ExampleGenerator.generate(
        (ThisBuild / baseDirectory).value / "examples",
        (Test / sourceManaged).value
      )
    }.taskValue,
    libraryDependencies ++= Seq(
      "org.ekrich"    %%% "sconfig"          % sconfig,
      "org.scalameta" %%% "munit"            % munit           % Test,
      "org.scalameta" %%% "munit-scalacheck" % munitScalaCheck % Test
    ),
    // SconfigDefectsSpec asserts what sconfig *should* do, so it is red while those upstream bugs
    // are open. Scoped to the `test` task only, so `testOnly` can still run it on demand.
    Test / test / testOptions += Tests.Exclude(Seq("ww86.hocon_fmt.SconfigDefectsSpec"))
  )
  .jvmSettings(announceRuntime("core on the JVM"))
  .jsSettings(announceRuntime("core on Scala.js"))
  .nativeSettings(announceRuntime("core on Scala Native"))
  .platformsSettings(JSPlatform, NativePlatform)(
    // sconfig reaches for java.time, which neither the Scala.js nor the Scala Native javalib
    // carries. Provided, as sconfig itself declares it: an application supplies exactly one
    // implementation, and two of the same package clash at link time. The CLI gets
    // scala-java-time through cats-effect; this one only serves core's own tests.
    libraryDependencies += "org.ekrich" %%% "sjavatime" % sjavatime % Provided
  )

lazy val coreJVM    = core.jvm
lazy val coreJS     = core.js
lazy val coreNative = core.native

lazy val zio = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("zio"))
  .dependsOn(core)
  .settings(
    name := "hocon-fmt-zio",
    libraryDependencies ++= Seq(
      "dev.zio"       %%% "zio"         % zioVersion,
      "dev.zio"       %%% "zio-streams" % zioVersion,
      "org.scalameta" %%% "munit"       % munit % Test
    )
  )
  .platformsSettings(JVMPlatform, NativePlatform)(
    libraryDependencies += "org.scalameta" %%% "munit-scalacheck" % munitScalaCheck % Test,
    Seq(Compile, Test).map { configuration =>
      configuration / unmanagedSourceDirectories +=
        (ThisBuild / baseDirectory).value / "zio" / "jvm-native" / "src" / configuration.name / "scala"
    }
  )
  .platformsSettings(JSPlatform, NativePlatform)(
    libraryDependencies += "org.ekrich" %%% "sjavatime" % sjavatime
  )
  .jvmSettings(announceRuntime("ZIO adapter on the JVM"))
  .jsSettings(announceRuntime("ZIO text adapter on Scala.js"))
  .nativeSettings(announceRuntime("ZIO adapter on Scala Native"))

lazy val zioJVM    = zio.jvm
lazy val zioJS     = zio.js
lazy val zioNative = zio.native

/** The command line tool, on every platform: the native binary, the Node bundle behind the
  * pre-commit hook, and the JVM. Effects live here, in cats-effect, so `core` stays pure.
  */
lazy val cli = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("cli"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(core)
  .settings(
    name             := "hocon-fmt-cli",
    buildInfoPackage := "ww86.hocon_fmt",
    buildInfoKeys    := Seq[BuildInfoKey](version),
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect"       % catsEffect,
      "co.fs2"        %%% "fs2-io"            % fs2,
      "com.monovore"  %%% "decline-effect"    % decline,
      "org.typelevel" %%% "munit-cats-effect" % munitCatsEffect % Test
    )
  )
  // Blocking java.io for the standard streams; Scala.js has no System.in and keeps fs2 (see StdStreams).
  .platformsSettings(JVMPlatform, NativePlatform)(
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "cli" / "jvm-native" / "src" / "main" / "scala"
  )
  .jvmSettings(
    announceRuntime("cli on the JVM"),
    Compile / mainClass := Some("ww86.hocon_fmt.CmdApi"),
    // IOApp ends with System.exit, which must end a forked JVM and not sbt.
    run / fork := true,
    // A forked run starts in the project's directory; relative paths mean the one sbt runs in.
    run / baseDirectory := (ThisBuild / baseDirectory).value
  )
  .jsSettings(
    announceRuntime("cli on Scala.js"),
    scalaJSUseMainModuleInitializer := true,
    // fs2-io reaches Node's `fs` through `require`.
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .nativeSettings(
    announceRuntime("cli on Scala Native"),
    // The shipped binary is optimised; tests link in debug mode, which is several times faster.
    Compile / nativeConfig ~= {
      _.withBaseName("hocon-fmt").withMode(Mode.releaseFast).withLTO(LTO.thin)
    },
    Test / nativeConfig ~= { _.withMode(Mode.debug).withLTO(LTO.none) }
  )

val npmPackage = taskKey[File]("Assembles the npm package: the linked CLI and its package.json.")

cliJS / npmPackage := {
  val linked  = (cliJS / Compile / fullLinkJSOutput).value / "main.js"
  val staging = (cliJS / target).value / "npm-package"
  IO.delete(staging)
  IO.copyDirectory(file("npm"), staging)
  // npm links `bin` entries as executables, which needs the shebang to pick Node.
  val cli = staging / "hocon-fmt.js"
  IO.write(cli, "#!/usr/bin/env node\n" + IO.read(linked))
  cli.setExecutable(true)
  val manifest = staging / "package.json"
  IO.write(manifest, IO.read(manifest).replace(""""version": "0.0.0"""", s""""version": "${version.value}""""))
  staging
}

lazy val cliJVM    = cli.jvm
lazy val cliJS     = cli.js
lazy val cliNative = cli.native

val bundle = taskKey[File]("The playground's script: the optimised web module under a licence banner.")

/** The command line as a process, on every runtime: output encoding, how stdin and stdout are attached,
  * exit codes. `CmdApiSpec` runs inside the test JVM and cannot see those. Too slow for the `test`
  * sequence, since it links the Scala.js and Scala Native builds; run it with `sbt acceptance/test`.
  * The runtimes under test are handed over as system properties, so no script has to find them.
  */
lazy val acceptance = project
  .in(file("acceptance"))
  .settings(
    name           := "hocon-fmt-acceptance",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.lihaoyi"   %% "os-lib" % osLib % Test,
      "org.scalameta" %% "munit"  % munit % Test
    ),
    Test / fork := true,
    Test / javaOptions ++= Seq(
      s"-Dcli.jvm.classpath=${(cliJVM / Runtime / fullClasspath).value.files.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)}",
      s"-Dcli.node.main=${(cliJS / Compile / fastLinkJSOutput).value.getAbsolutePath}/main.js",
      s"-Dcli.native.binary=${(cliNative / Compile / nativeLink).value.getAbsolutePath}"
    )
  )

/** The formatter as a script for web pages, behind the playground: one global, `HoconFormatter`,
  * with a small JavaScript API; see docs/playground.md. A classic script rather than an ES module,
  * so a page that loads it works from `file://` as well.
  */
lazy val web = project
  .in(file("web"))
  .enablePlugins(ScalaJSPlugin, BuildInfoPlugin)
  .dependsOn(coreJS)
  .settings(
    name           := "hocon-fmt-web",
    publish / skip := true,
    announceRuntime("web on Scala.js"),
    libraryDependencies ++= Seq(
      "org.ekrich"    %%% "sjavatime" % sjavatime,
      "org.scalameta" %%% "munit"     % munit % Test
    ),
    buildInfoPackage := "ww86.hocon_fmt.web",
    buildInfoKeys    := Seq[BuildInfoKey](version),
    // The tests run the optimised script a page loads, where only exported names survive.
    Test / scalaJSStage := FullOptStage,
    Seq(Compile, Test).map(_ / fullLinkJS / scalaJSLinkerConfig ~= (_.withClosureCompilerIfAvailable(true))),
    bundle := {
      val linked = (Compile / fullLinkJSOutput).value / "main.js"
      val script = target.value / "bundle" / "hocon-fmt.js"
      // The source map is not shipped, so neither is the comment that points to it.
      val code = IO.readLines(linked).filterNot(_.startsWith("//# sourceMappingURL=")).mkString("\n")
      IO.write(script, s"/*! hocon-fmt ${version.value} | GPL-3.0 | ${homepage.value.get} */\n$code\n")
      script
    }
  )

/** The project site: the formatter presented, a playground on the core directly, and the author's
  * contributions to the libraries it depends on. One static page served by GitHub Pages from this
  * repository (hocon-fmt.ww86.eu); the Laminar app calls `coreJS` itself, with no JavaScript API
  * in between. Not published; see docs/site.md.
  */
val build =
  taskKey[File]("Assembles the Pages output into site/target/site: index.html, the optimised script, CNAME, .nojekyll.")

lazy val site = project
  .in(file("site"))
  .enablePlugins(ScalaJSPlugin, BuildInfoPlugin)
  .dependsOn(coreJS)
  .settings(
    name := "hocon-fmt-site",
    Compile / sourceGenerators += Def.task {
      ExampleGenerator.generate(
        (ThisBuild / baseDirectory).value / "examples",
        (Compile / sourceManaged).value
      )
    }.taskValue,
    publish / skip := true,
    announceRuntime("site on Scala.js"),
    // sconfig reaches for java.time, which the Scala.js javalib does not carry; the site is the
    // application here, so it supplies the one implementation, the way `web` does.
    libraryDependencies ++= Seq(
      "com.raquo"     %%% "laminar"   % laminar,
      "org.ekrich"    %%% "sjavatime" % sjavatime,
      "org.scalameta" %%% "munit"     % munit % Test
    ),
    buildInfoPackage := "ww86.hocon_fmt.site",
    buildInfoKeys    := Seq[BuildInfoKey](version),
    // The linked script runs the page itself on load; tests link their own module without it.
    Compile / scalaJSUseMainModuleInitializer := true,
    Compile / fullLinkJS / scalaJSLinkerConfig ~= (_.withClosureCompilerIfAvailable(true)),
    build := {
      val out = target.value / "site"
      IO.delete(out)
      IO.createDirectory(out)
      // The default NoModule kind links one classic script, so a `<script>` tag loads it and the
      // page works from file:// as well. The source map is not shipped, so neither is the comment
      // that points to it.
      val linked = (Compile / fullLinkJSOutput).value / "main.js"
      val code   = IO.readLines(linked).filterNot(_.startsWith("//# sourceMappingURL=")).mkString("\n")
      IO.write(out / "main.js", code)
      val index = (Compile / resources).value.find(_.getName == "index.html")
      IO.copyFile(index.getOrElse(sys.error("site resources are missing index.html")), out / "index.html")
      IO.write(out / "CNAME", "hocon-fmt.ww86.eu\n")
      // A Pages branch is served through Jekyll unless it is told not to; nothing here wants a
      // preprocessor, and this keeps the three files above byte for byte.
      IO.write(out / ".nojekyll", "")
      out
    }
  )

addCommandAlias(
  "crossCompile",
  Seq(coreJVM, coreJS, coreNative, cliJVM, cliJS, cliNative, zioJVM, zioJS, zioNative, web, site)
    .map(p => s"${p.id}/Test/compile")
    .mkString("; ")
)
// On every platform: sconfig's Scala.js and Scala Native builds have defects of their own.
addCommandAlias(
  "libraryDefects",
  Seq(coreJVM, coreJS, coreNative).map(p => s"${p.id}/testOnly ww86.hocon_fmt.SconfigDefectsSpec").mkString("; ")
)

/** Timings of each formatter phase on each platform; see `scripts/bench.py`. Not published. The
  * mutable loop in `Bench.measure` is deliberate: an allocation-free timing loop is the one place
  * where the functional style would distort what it measures.
  */
lazy val bench = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("bench"))
  .dependsOn(core)
  .settings(
    name           := "hocon-fmt-bench",
    publish / skip := true
  )
  .jvmSettings(run / fork := true)
  .jsSettings(
    scalaJSUseMainModuleInitializer := true,
    // Timings of the fast-optimised output would describe a build nobody ships.
    scalaJSStage := FullOptStage
  )
  .platformsSettings(JSPlatform, NativePlatform)(
    libraryDependencies += "org.ekrich" %%% "sjavatime" % sjavatime
  )
  .nativeSettings(nativeConfig ~= { _.withMode(Mode.releaseFast).withLTO(LTO.thin) })

lazy val benchJVM    = bench.jvm
lazy val benchJS     = bench.js
lazy val benchNative = bench.native

/** The sbt 1.x plugin. sbt loads plugins with Scala 2.12, which cannot link against this Scala 3
  * build, so the plugin resolves the core at run time and calls it through `JvmFacade` in an
  * isolated class loader, the way sbt-scalafmt runs scalafmt. Its behaviour is covered by the
  * scripted tests in `sbt-plugin/src/sbt-test`, run with `sbtPluginTest`: each starts a fresh
  * sbt, which is too slow for the `test` sequence.
  */
lazy val sbtPlugin = project
  .in(file("sbt-plugin"))
  .enablePlugins(SbtPlugin, BuildInfoPlugin)
  .settings(
    name         := "sbt-hocon-fmt",
    scalaVersion := "2.12.21",
    // ThisBuild's options are Scala 3's; the same checks, as far as 2.12 has them.
    scalacOptions := Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Xlint",
      "-Ywarn-unused",
      "-Ywarn-value-discard"
    ),
    // The coordinates the plugin resolves the formatter by, so the two are released in lockstep.
    buildInfoPackage := "ww86.hocon_fmt.sbt",
    buildInfoObject  := "FormatterArtifact",
    buildInfoKeys    := Seq[BuildInfoKey](
      "organization" -> (coreJVM / organization).value,
      "name"         -> s"${(coreJVM / moduleName).value}_${(coreJVM / scalaBinaryVersion).value}",
      "version"      -> (coreJVM / version).value,
      "scalaVersion" -> (coreJVM / scalaVersion).value
    ),
    scriptedLaunchOpts += s"-Dplugin.version=${version.value}",
    // The tests read sbt's logs; on CI, sbt colours them, and the escape codes hide `[warn]`.
    scriptedLaunchOpts += "-Dsbt.log.noformat=true",
    // The plugin fetches the core by its coordinates, so scripted needs it published first.
    scriptedDependencies := scriptedDependencies.dependsOn(coreJVM / publishLocal).value
  )

addCommandAlias("sbtPluginTest", "sbtPlugin/scripted")

// What a release uploads: the libraries and the sbt plugin, signed. Stops at the upload, so the
// deployment waits in the portal until someone clicks Publish.
addCommandAlias(
  "publishRelease",
  // `sbtPlugin` is also an sbt key, so the plugin's project is named by its id.
  Seq(coreJVM.id, coreJS.id, coreNative.id, cliJVM.id, zioJVM.id, zioJS.id, zioNative.id, "sbtPlugin")
    .map(id => s"$id/publishSigned")
    .mkString("; ") + "; sonaUpload"
)
