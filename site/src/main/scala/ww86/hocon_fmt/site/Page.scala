package ww86.hocon_fmt.site

import com.raquo.laminar.api.L.*

/** The whole page, in the owner's order: what the formatter is in one line and three bullets with
  * a quick start, the playground, how to use it, the known limits, and — collapsed by default —
  * the work upstream.
  */
object Page {

  def apply(): HtmlElement =
    div(
      cls := "page",
      headerTag(
        h1("hocon-fmt"),
        p(cls := "tagline", "hocon-fmt formats HOCON configuration files consistently."),
        ul(
          cls := "tldr",
          li("A file it cannot format is refused, never corrupted: it is left byte for byte as it was."),
          li("One core in every channel: CLI, pre-commit, sbt, Gradle, Maven, Mill, npm, Python, the Java API."),
          li(a(href := "#playground", "Try it below"), ": the playground runs in your browser.")
        ),
        pre(
          cls := "quick-start",
          code(
            """pipx install hocon-fmt                # the native binary, from the Python wheel
              |hocon-fmt --check application.conf    # report; without --check it rewrites""".stripMargin
          )
        )
      ),
      Playground(),
      UseIt(),
      KnownLimits(),
      ContributionsView(),
      pageFooter()
    )

  private def pageFooter(): HtmlElement = {
    val version = BuildInfo.version
    val source  = if version.endsWith("-SNAPSHOT") then Repo.url else Repo.tree(s"v$version")
    footerTag(
      cls := "footer",
      span(s"hocon-fmt $version · "),
      a(href := source, "source"),
      " · ",
      a(href := s"${Repo.url}/blob/main/LICENSE", "GPL-3.0"),
      " · ",
      a(href := "https://ww86.eu", "ww86.eu")
    )
  }
}
