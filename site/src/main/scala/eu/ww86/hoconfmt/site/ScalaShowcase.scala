package eu.ww86.hoconfmt.site

import com.raquo.laminar.api.L.*
import org.scalajs.dom

import scala.scalajs.js

object ScalaShowcase {
  // Release changes this only after the coordinates resolve from Maven Central.
  val released     = false
  val scalaVersion = "3.8.2"

  def dependencyError(code: String): Option[String] = {
    val compact = code.toLowerCase.replaceAll("\\s+", "")
    Option.when(
      compact.contains("//>") || compact.contains("$ivy") ||
        compact.contains("librarydependencies") || compact.contains("scala-cli")
    )("Dependencies are fixed to this API. Remove dependency or scala-cli directives.")
  }

  def buildConfig(integration: UseIt.Integration): String = {
    val module   = integration.module.getOrElse("hocon-fmt-core")
    val artifact =
      if (integration.id == "java") module else if (integration.id == "scalajs") s"${module}_sjs1_3" else s"${module}_3"
    val modules =
      if (integration.id == "cats" || integration.id == "zio") List("hocon-fmt-core_3", artifact) else List(artifact)
    val dependencies = modules.map(name => s""""eu.ww86" % "$name" % "0.1.0"""")
    val runtime      = if (integration.id == "scalajs") List(""""org.ekrich" % "sjavatime_sjs1_3" % "1.5.0"""") else Nil
    s"libraryDependencies ++= Seq(${(dependencies ++ runtime).mkString(", ")})"
  }

  def scastieUrl(integration: UseIt.Integration, code: String): Either[String, String] =
    dependencyError(code).toLeft {
      val target = if (integration.id == "scalajs") {
        js.Dynamic.literal(Js = js.Dynamic.literal(scalaVersion = scalaVersion, scalaJsVersion = "1.22.0"))
      } else js.Dynamic.literal(Scala3 = js.Dynamic.literal(scalaVersion = scalaVersion))
      val inputs = js.Dynamic.literal(SbtInputs =
        js.Dynamic.literal(
          isWorksheetMode = false,
          code = code,
          target = target,
          libraries = js.Array(),
          librariesFromList = js.Array(),
          sbtConfigExtra = buildConfig(integration),
          sbtConfigSaved = js.undefined,
          sbtPluginsConfigExtra = "",
          sbtPluginsConfigSaved = js.undefined,
          isShowingInUserProfile = false,
          forked = js.undefined
        )
      )
      s"https://scastie.scala-lang.org/?inputs=${js.URIUtils.encodeURIComponent(js.JSON.stringify(inputs))}"
    }

  def apply(integration: UseIt.Integration, source: Var[String]): HtmlElement = {
    val error = source.signal.map(dependencyError)
    div(
      p(
        "Edit this example or enter your own code. The build sent by this page contains only this API and its required dependencies."
      ),
      pre(code(buildConfig(integration))),
      label(
        if (integration.id == "java") "Java code (also callable from Kotlin)" else "Scala code",
        textArea(
          cls  := "conf showcase-code",
          rows := 14,
          value <-- source.signal,
          onInput.mapToValue --> source.writer
        )
      ),
      p(role := "status", child.text <-- error.map(_.getOrElse(""))),
      button(
        typ := "button",
        "Run in Scastie",
        disabled <-- error.map(e => !released || e.nonEmpty || integration.id == "java"),
        onClick --> { _ =>
          if (released && integration.id != "java") {
            scastieUrl(integration, source.now()).foreach { url =>
              // No visitor code is evaluated in the page; Scastie owns the remote execution.
              val _ = dom.window.open(url, "_blank", "noopener,noreferrer")
            }
          }
        }
      ),
      p(
        cls := "muted",
        (if (released) "" else "Run in Scastie works after the first release. ") +
          "Scastie can change its own build settings; this restriction applies to what our page sends."
      ),
      if (integration.id == "java")
        p(
          "Scastie accepts Scala sources. This Java example is editable for copying into a Java project; the Scala runner cannot execute it."
        )
      else emptyNode,
      if (integration.id == "scalajs")
        p(
          "Scala.js uses the core module. JavaScript callers use the separate web bundle's HoconFormatter global; web is not a Maven artifact."
        )
      else emptyNode,
      p(
        cls := "muted",
        "Scala execution runs remotely in Scastie. Local Scala execution is not available on this page yet."
      )
    )
  }
}
