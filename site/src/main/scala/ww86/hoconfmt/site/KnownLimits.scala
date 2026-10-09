package ww86.hoconfmt.site

import com.raquo.laminar.api.L.*

/** The page's "Known limits": the short list of what the formatter does not do, each backed by a
  * reproduction in docs/limitations.md. The upstream pull requests behind the first two bullets
  * live in the collapsed section below.
  */
object KnownLimits {

  def apply(): HtmlElement =
    sectionTag(
      idAttr := "limits",
      h2("Known limits"),
      ul(
        li(
          "sconfig mis-renders some inputs — an env override like ",
          code("host = ${?HOST}"),
          ", or an object concatenated with a substitution — so the formatter refuses rather than ",
          "write them; the fixes are proposed upstream, most merged and awaiting a release."
        ),
        li(
          "A comment that ends up attached to no field — every banner header above a blank line — ",
          "is refused rather than dropped. The most common real-world refusal."
        ),
        li(
          "Formatting normalises spelling on purpose: ",
          code("//"),
          " comments become ",
          code("#"),
          ", nested objects flatten to dotted paths, numbers are canonicalised."
        ),
        li(
          "A file named ",
          code(".json"),
          " or ",
          code(".properties"),
          " is refused by name: formatting it would write HOCON under another format's name."
        ),
        li(
          "The duplicate report reads one file at a time: it opens no include and resolves no ",
          "substitution, so it stays silent where either could still matter."
        )
      ),
      p(
        "Every one of these, with reproductions and the inputs they hit: ",
        a(href := Status.limitationsPage, "docs/limitations.md"),
        "."
      )
    )
}
