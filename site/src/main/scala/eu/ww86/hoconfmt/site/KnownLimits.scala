package eu.ww86.hoconfmt.site

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
          "write them. The playground's fork formats some of these inputs; the JVM core uses released sconfig 2.0.0."
        ),
        li(
          "Leading banner headers are preserved verbatim. Detached comments elsewhere can still be refused ",
          "rather than dropped; the playground's fork keeps more of them."
        ),
        li(
          "When a key repeats, sconfig merges the objects and may reverse the order of their comments. ",
          "No comment is lost, but the order is not checked, so such a file is formatted, not refused."
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
