package ww86.hocon_fmt.site

import com.raquo.laminar.api.L.*

/** The whole page: the formatter presented, the playground, the upstream work, the footer. */
object Page:

  def apply(): HtmlElement =
    div(
      cls := "page",
      headerTag(
        h1("hocon-fmt"),
        p(
          cls := "tagline",
          "A formatter for HOCON configuration files — it would rather refuse a file than corrupt it."
        )
      ),
      Playground(),
      ContributionsView(),
      pageFooter()
    )

  private def pageFooter(): HtmlElement =
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
