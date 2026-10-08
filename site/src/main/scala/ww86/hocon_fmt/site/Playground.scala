package ww86.hocon_fmt.site

import com.raquo.laminar.api.L.*

import ww86.hocon_fmt.{ExampleData, Verdict}

/** The page's first two parts: what the formatter is, and the playground on the core itself.
  * The third part lives in [[ContributionsView]].
  */
object Playground {

  def apply(): HtmlElement = {
    val first   = ExampleData.showcase.headOption.fold("")(_.input)
    val input   = Var(first)
    val verdict = verdicts(input.signal, first)

    div(
      sectionTag(
        idAttr := "formatter",
        h2("What it is"),
        p(
          "HOCON is the configuration format of lightbend/config and its Scala port sconfig: JSON ",
          "with a friendlier face — comments, ",
          code("include"),
          " directives, substitutions, and fields written without quotes or braces. ",
          "Real files drift: indentation wanders, ",
          code("="),
          " and ",
          code(":"),
          " mix, nested ",
          "objects grow inconsistent. hocon-fmt tidies a file by parsing it with sconfig and ",
          "rendering it back with fixed options: ",
          code("//"),
          " comments become ",
          code("#"),
          ", ",
          code("="),
          " becomes ",
          code(":"),
          ", nested objects flatten to dotted paths. The meaning stays; the spelling does not."
        ),
        p("Two things it does that a plain parse-render round trip does not:"),
        ul(
          li(
            strong("Includes survive."),
            " Parsing resolves an ",
            code("include"),
            " directive and keeps nothing to render, so a plain round trip deletes it. The ",
            "formatter carries each whole statement across the round trip and puts it back."
          ),
          li(
            strong("It refuses rather than corrupts."),
            " Before handing text back it checks that ",
            "the output parses again, that a second pass would not change it, and that no comment ",
            "or include went missing. A file that fails any of this is left byte for byte as it ",
            "was, reported with the reason, without failing the run."
          )
        ),
        p(
          "One Scala 3 core serves a command line tool (a native binary, Node.js and the JVM), ",
          "pre-commit hooks, and plugins for sbt, Gradle, Maven and Mill."
        ),
        p(
          cls := "privacy",
          "The playground below runs this page's own copy of that core, in your browser: nothing ",
          "you type or paste here ever leaves the page. The only network requests the page makes ",
          "are the read-only lookups of the contribution list on api.github.com, further down."
        ),
        p(
          "Nothing is published yet, so there are no installation commands to show; they will ",
          "appear here with the first release. Until then, the source is on GitHub: ",
          a(href := Repo.url, "kastoestoramadus/hocon-fmt"),
          "."
        )
      ),
      sectionTag(
        idAttr := "playground",
        h2("Try it"),
        p(
          cls := "muted",
          "Formatting happens as you type, here in the page. Pick an example or paste your own."
        ),
        div(
          cls := "examples",
          ExampleData.showcase.map { example =>
            button(
              cls   := "example",
              tpe   := "button",
              title := example.shows,
              example.title,
              onClick.mapTo(example.input) --> input
            )
          }
        ),
        div(
          cls := "panes",
          label(
            cls := "pane",
            span(cls := "pane-title", "Input"),
            textArea(
              cls         := "conf",
              spellCheck  := false,
              placeholder := "paste HOCON here",
              value <-- input.signal,
              onInput.mapToValue --> input
            )
          ),
          label(
            cls := "pane",
            span(cls := "pane-title", "Output"),
            textArea(
              cls      := "conf",
              readOnly := true,
              value <-- verdict.map(out)
            )
          )
        ),
        p(cls := "status", aria.live := "polite", child <-- verdict.map(statusLine)),
        // UPSTREAM-SCONFIG: delete this line, and the page runs on released sconfig again, once
        // ekrich/sconfig releases the option (#646/#647); docs/site.md, "Returning to upstream sconfig".
        p(
          cls := "muted",
          "The playground runs a development build of sconfig — the detached-comment fix (",
          a(href := "https://github.com/ekrich/sconfig/issues/646", "#646"),
          ", draft ",
          a(href := "https://github.com/ekrich/sconfig/pull/647", "#647"),
          ") and the other fixes merged since its last release — so it keeps comments, and renders ",
          "merges, that the released CLI still refuses; everything published uses released sconfig unchanged."
        )
      )
    )
  }

  /** One verdict per settled input; kept separate from the DOM for reactive tests. Typed text has
    * no file name behind it, so a refusal names the playground as the place a parse tripped.
    */
  private[site] def verdicts(input: Signal[String], first: String, debounceMs: Int = 150): Signal[(String, Verdict)] = {
    input.distinct.changes
      .debounce(debounceMs)
      .startWith(first)
      .distinct
      .map(text => text -> Verdict.of(text, "playground"))
  }

  /** The output pane shows the formatting; a refused or settled text stays exactly as typed. */
  private def out(entry: (String, Verdict)): String = entry match {
    case (_, Verdict.NeedsFormatting(formatted)) => formatted
    case (text, _)                               => text
  }

  private def statusLine(entry: (String, Verdict)): HtmlElement = {
    val (text, verdict) = entry
    Status.of(verdict, text) match {
      case Status.Formatted(changed) =>
        span(
          strong("Formatted"),
          s" — $changed ${if changed == 1 then "line" else "lines"} changed."
        )
      case Status.AlreadyFormatted =>
        span(strong("Already formatted"), ".")
      case Status.LeftUnchanged(_, reason, explanation, learnMore) =>
        span(
          strong("Left unchanged"),
          s" — $reason. ",
          explanation + " ",
          learnMore.map(url =>
            a(href := url, target := "_blank", rel := "noopener noreferrer", "Why the formatter refuses this")
          )
        )
    }
  }
}
