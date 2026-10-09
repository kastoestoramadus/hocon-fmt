package ww86.hocon_fmt.site

import com.raquo.laminar.api.L.*
import org.scalajs.dom

object UseIt {
  val usageDoc = "https://github.com/kastoestoramadus/hocon-fmt/blob/main/docs/usage.md"
  // The hooks pin PyPI and npm versions wave 1 does not publish; the same sentence heads the
  // pre-commit section of docs/usage.md.
  val wave2Note = "The hooks ship in wave 2: they pin PyPI and npm versions published only then."
  final case class Integration(id: String, title: String, module: Option[String] = None)
  val integrations = List(
    Integration("native", "CLI native"),
    Integration("npm", "CLI npm"),
    Integration("jvm", "CLI JVM"),
    Integration("pre-commit", "pre-commit"),
    Integration("sbt", "sbt"),
    Integration("gradle", "Gradle"),
    Integration("maven", "Maven"),
    Integration("mill", "Mill"),
    Integration("java", "Java / Kotlin", Some("hocon-fmt-java-api")),
    Integration("core", "Core", Some("hocon-fmt-core")),
    Integration("cats", "cats-effect", Some("hocon-fmt-cats")),
    Integration("zio", "ZIO", Some("hocon-fmt-zio")),
    Integration("scalajs", "Scala.js", Some("hocon-fmt-core"))
  )

  def fromHash(hash: String): String =
    integrations.find(i => hash == s"#use-it-${i.id}").fold("native")(_.id)

  def apply(): HtmlElement = {
    val selected = Var(fromHash(dom.window.location.hash))
    val sources  = UseItExamples.snippets.map { case (id, code) => id -> Var(code) }
    sectionTag(
      idAttr := "use-it",
      h2("Use it"),
      p(
        "Format rewrites changed files; check writes nothing and fails on unformatted files. Refused files are reported and left untouched."
      ),
      div(
        cls := "output-tabs use-it-tabs",
        integrations.map { integration =>
          button(
            typ := "button",
            integration.title,
            aria.pressed <-- selected.signal.map(id => (id == integration.id).toString),
            onClick --> { _ =>
              selected.set(integration.id)
              dom.window.location.hash = s"#use-it-${integration.id}"
            }
          )
        }
      ),
      windowEvents(_.onHashChange) --> { _ => selected.set(fromHash(dom.window.location.hash)) },
      child <-- selected.signal.map { id =>
        integrations.find(_.id == id).fold(div()) { integration =>
          div(
            cls := "channel",
            h3(integration.title),
            integration.module.fold[HtmlElement](
              div(
                pre(code(UseItExamples.commands.getOrElse(id, ""))),
                if (id == "pre-commit") p(wave2Note) else emptyNode,
                p(UseItExamples.actions.getOrElse(id, Nil).map(command => code(command + " "))),

                p(
                  "Setup and format/check commands: ",
                  a(
                    href := s"$usageDoc#${if (id == "native" || id == "npm" || id == "jvm") "command-line" else id}",
                    "docs/usage.md"
                  )
                )
              )
            )(_ => ScalaShowcase(integration, sources.getOrElse(id, Var(""))))
          )
        }
      },
      p(cls := "muted", "Coordinates describe the first release; until then, run from a checkout."),
      h3("Configure"),
      p(
        "The command line takes its style from ",
        code(".hocon-fmt.conf"),
        ", a HOCON file looked up beside the formatted file and up to the checkout root: ",
        code("separator"),
        " (",
        code("\":\""),
        " or ",
        code("\"=\""),
        "), ",
        code("double-indent"),
        ", ",
        code("simplify-nested-objects"),
        ". Flags — ",
        code("--separator =|:"),
        ", ",
        code("--double-indent"),
        ", ",
        code("--simplify-nested-objects"),
        ", ",
        code("--config <file>"),
        " — win over the file, which wins over the default. A key ",
        "defined again is reported with both lines; the report is a warning unless ",
        code("fail-on-duplicates = true"),
        " (or ",
        code("--fail-on-duplicates"),
        ") makes it fail the run."
      )
    )
  }
}
