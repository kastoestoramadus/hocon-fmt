package ww86.hocon_fmt.site

import com.raquo.laminar.api.L.*

/** The page's "Use it": install and one usage line per channel that exists today, every command
  * and coordinate copied from docs/usage.md, and the few choices the formatter takes. What the
  * formatter refuses is [[KnownLimits]]'s subject, not this one's.
  */
object UseIt {

  val usageDoc = "https://github.com/kastoestoramadus/hocon-fmt/blob/main/docs/usage.md"

  def apply(): HtmlElement =
    sectionTag(
      idAttr := "use-it",
      h2("Use it"),
      p(
        "Every channel runs the same core, which works in three ways: ",
        strong("format"),
        " rewrites what changed, ",
        strong("check"),
        " writes nothing and fails on an unformatted file, and a refused file is ",
        "reported and left byte for byte as it was."
      ),
      channel(
        "Command line",
        p(
          "One program in three builds — a native binary installed from the Python wheel, an npm ",
          "package, and a JVM build from a checkout:"
        ),
        snippet(
          """pipx install hocon-fmt      # native binary, from the Python wheel
            |npx hocon-fmt               # Node
            |sbt "cliJVM/run <args>"     # JVM, from a checkout""".stripMargin
        ),
        p(
          code("hocon-fmt --check <file>..."),
          " checks; without ",
          code("--check"),
          " it rewrites. Native binaries are also on the GitHub releases page."
        )
      ),
      channel(
        "pre-commit",
        p(
          "The hooks run the native binary from the wheel; where there is no native build, such ",
          "as Windows, use ",
          code("hocon-fmt-node"),
          " and ",
          code("hocon-fmt-check-node"),
          ":"
        ),
        snippet(
          """repos:
            |  - repo: https://github.com/kastoestoramadus/hocon-fmt
            |    rev: v0.1.0
            |    hooks:
            |      - id: hocon-fmt          # rewrites files; the commit stops so you can stage them
            |      # - id: hocon-fmt-check  # or only report""".stripMargin
        )
      ),
      channel(
        "sbt",
        snippet("""// project/plugins.sbt
            |addSbtPlugin("eu.ww86" % "sbt-hocon-fmt" % "0.1.0")""".stripMargin),
        p(
          code("hoconFormat"),
          " rewrites, ",
          code("hoconFormatCheck"),
          " fails on an unformatted file; neither is wired into compile or test."
        )
      ),
      channel(
        "Gradle",
        snippet("""plugins {
            |    id("eu.ww86.hocon-fmt") version "0.1.0"
            |}""".stripMargin),
        p(
          code("hoconFormat"),
          " rewrites; ",
          code("hoconFormatCheck"),
          " fails on an unformatted file and runs as part of ",
          code("check"),
          "."
        )
      ),
      channel(
        "Maven",
        snippet(
          """<plugin>
            |  <groupId>eu.ww86</groupId>
            |  <artifactId>hocon-fmt-maven-plugin</artifactId>
            |  <version>0.1.0</version>
            |</plugin>""".stripMargin
        ),
        p(
          code("mvn hocon-fmt:format"),
          " rewrites; the ",
          code("check"),
          " goal binds to ",
          code("verify"),
          "."
        )
      ),
      channel(
        "Mill",
        snippet("""//| mvnDeps:
            //| - eu.ww86::mill-hocon-fmt::0.1.0""".stripMargin),
        p(
          "Mix in ",
          code("HoconFormatterModule"),
          " (Mill 1.1.4 or newer); ",
          code("./mill __.hoconFormat"),
          " rewrites, ",
          code("__.hoconFormatCheck"),
          " fails on an unformatted file."
        )
      ),
      channel(
        "Java API",
        p(
          code("eu.ww86:hocon-fmt-java-api"),
          " puts the core behind records a Java 21 ",
          code("switch"),
          " can exhaust; Kotlin's ",
          code("when"),
          " is held to the full set too."
        ),
        snippet(
          """import ww86.hocon_fmt.java.HoconFmt;
            |import ww86.hocon_fmt.java.Verdict;
            |
            |Verdict verdict = HoconFmt.checkFile(path);
            |HoconFmt.formatFile(path);   // rewrites only when the formatted text differs""".stripMargin
        )
      ),
      p(
        cls := "muted",
        "Coordinates are the ones documented for the first release; until it is out, every channel ",
        "runs from a checkout. Each channel's full setup: ",
        a(href := usageDoc, "docs/usage.md"),
        "."
      ),
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

  private def channel(title: String, parts: Modifier[HtmlElement]*): HtmlElement = {
    val all: List[Modifier[HtmlElement]] = List(cls := "channel", h3(title)) ++ parts.toList
    div(all*)
  }

  private def snippet(codeText: String): HtmlElement =
    pre(code(codeText))
}
