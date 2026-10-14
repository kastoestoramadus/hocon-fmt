package eu.ww86.hoconfmt.site

import com.raquo.laminar.api.L.*

/** The whole page, in the owner's order: what the formatter is in one line and three bullets with
  * a quick start, the playground, the release scope, how to use it, refusals and known limits,
  * and — collapsed by default — the work upstream.
  */
object Page {

  def apply(): HtmlElement =
    div(
      cls := "page",
      headerTag(
        cls := "page-head",
        h1("hocon-fmt"),
        deployStamp(),
        p(cls := "tagline", "hocon-fmt formats HOCON configuration files consistently."),
        ul(
          cls := "tldr",
          li("A file it cannot format is refused, never corrupted: it is left byte for byte as it was."),
          li("The first release covers the JVM core and sbt 1.x, Gradle and Maven plugins."),
          li(a(href := "#playground", "Try it below"), ": the playground runs in your browser.")
        ),
        pre(
          cls := "quick-start",
          code(
            """# from a checkout; check writes nothing
              |sbt "cliJVM/run --check application.conf"""".stripMargin
          )
        )
      ),
      Playground(),
      releaseScope(),
      UseIt(),
      releaseRefusals(),
      KnownLimits(),
      ContributionsView(),
      pageFooter()
    )

  private def releaseScope(): HtmlElement =
    sectionTag(
      idAttr := "release-scope",
      cls    := "release-scope",
      h2("What v0.1.0 covers"),
      p(
        "The first release is the JVM core and the sbt 1.x, Gradle and Maven plugins. ",
        "Native binaries, npm, PyPI, Mill and sbt 2 plugins follow later."
      )
    )

  private def releaseRefusals(): HtmlElement =
    sectionTag(
      idAttr := "release-refusals",
      cls    := "release-refusals",
      h2("Refusals in this release"),
      p(
        "v0.1.0 ships with the current refusal list: files the formatter cannot prove safe are refused. ",
        "A JVM corpus check measured on 2026-10-13 refused 310 of 1,257 files (24.7%). ",
        "The corpus includes parser test fixtures and non-HOCON .conf files; this is not a production prevalence estimate. ",
        "The refusal list has not been reviewed by hand yet; the second release tightens it."
      ),
      p(
        "For example, ",
        code("""include "defaults.conf", zone = "us""""),
        " is an include and a field sharing one line. ",
        "The command line refuses this input because rendering could move the include across the field and change which value wins; the file stays byte for byte as it was. ",
        "The playground formats this input in the same order using its sconfig fork. ",
        "Refusal never writes or breaks a file. See ",
        a(href := Status.limitationsPage, "docs/limitations.md"),
        "."
      )
    )

  /** The build's mark in the corner: what was deployed and when, from the build rather than the
    * browser. The stylesheet puts it top-right on a desktop and in the flow under the title on a
    * phone, where there is no corner to spare.
    */
  private def deployStamp(): HtmlElement = {
    val stamp = DeployStamp.of(BuildInfo.deploySha, BuildInfo.deployTime)
    span(
      cls := "deploy muted",
      stamp.lead,
      stamp.sha.map { case (short, url) => a(href := url, short) }
    )
  }

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
