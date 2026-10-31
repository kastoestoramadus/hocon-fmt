package eu.ww86.hoconfmt.site

import com.raquo.laminar.api.L.*

import org.ekrich.config.{ConfigFactory, ConfigFormatOptions, ConfigRenderOptions, ConfigResolveOptions, ConfigUtil}
import org.scalajs.dom

import scala.jdk.CollectionConverters.*
import scala.util.Try

import eu.ww86.hoconfmt.{
  DuplicateReport,
  ExampleData,
  Finding,
  FormatOptions,
  HoconFormatter,
  IncludeStatements,
  Separator,
  Verdict
}

/** The page's playground, on the core itself, with one privacy line and one short upstream line
  * per example. The page's other parts live in [[UseIt]], [[KnownLimits]] and [[ContributionsView]].
  */
object Playground {

  def apply(): HtmlElement = {
    val first       = ExampleData.showcase.headOption.fold("")(_.input)
    val input       = Var(first)
    val options     = Var(FormatOptions.default)
    val resolved    = Var(false)
    val paneHeight  = Var(Option.empty[Int])
    val settled     = settledText(input.signal, first, 150)
    val paneHeights = paneHeight.signal.map(_.fold("")(px => s"${px}px"))
    val verdict     = settled.combineWith(options.signal).map { (text, chosen) =>
      text -> Verdict.of(text, "playground", chosen)
    }

    sectionTag(
      idAttr := "playground",
      h2("Try it"),
      p(
        cls := "privacy",
        "The playground runs this page's own copy of the formatter core, in your browser: nothing ",
        "you type or paste here ever leaves the page. The only network requests are the read-only ",
        "lookups of the collapsed upstream section at the bottom."
      ),
      p(
        cls := "muted",
        "Formatting happens as you type, here in the page. Pick an example or paste your own."
      ),
      div(cls := "examples", ExampleData.showcase.map(exampleButton(_, input))),
      div(
        cls := "more-examples",
        h3("More examples"),
        div(cls := "examples", ExampleData.more.map(exampleButton(_, input)))
      ),
      p(
        cls := "story",
        child <-- settled.map { text =>
          (ExampleData.showcase ++ ExampleData.more)
            .find(_.input == text)
            .map { example =>
              span(
                example.story,
                example.source.url.map(url =>
                  span(
                    " Source: ",
                    a(href := url, "Apache Pekko"),
                    s" (${example.source.licence.getOrElse("")}, sha ${example.source.sha.getOrElse("")}). ",
                    a(href := "Apache-2.0.txt", "Licence"),
                    " · ",
                    a(href := "NOTICE", "Attribution")
                  )
                )
              )
            }
            .getOrElse(span())
        }
      ),
      div(
        cls := "upstream-note",
        child <-- settled.map { text =>
          (ExampleData.showcase ++ ExampleData.more)
            .find(_.input == text)
            .flatMap { example =>
              example.upstream.map { note =>
                // One short line: what the published core does, the report, our fix, and where it stands.
                p(
                  strong("Published core: "),
                  example.now.replace("refused:", "refuses: ").replace("-", " ") + ". ",
                  "Upstream ",
                  a(href := note.issue, "#" + note.issue.split("/").last),
                  note.fix.map(url => span(", fix ", a(href := url, "#" + url.split("/").last))),
                  ". ",
                  note.state
                )
              }
            }
            .getOrElse(span())
        }
      ),
      div(
        cls := "style-options",
        button(
          cls := "separator-option",
          tpe := "button",
          "Separator: ",
          child.text <-- options.signal.map(o => if o.separator == Separator.Equals then "=" else ":"),
          onClick --> { _ =>
            options.update(o =>
              o.copy(separator = if o.separator == Separator.Equals then Separator.Colon else Separator.Equals)
            )
          }
        ),
        button(
          cls := "nesting-option",
          tpe := "button",
          child.text <-- options.signal.map(o => if o.simplifyNestedObjects then "Keep nesting" else "Flatten nesting"),
          onClick --> { _ => options.update(o => o.copy(simplifyNestedObjects = !o.simplifyNestedObjects)) }
        ),
        button(
          cls := "indent-option",
          tpe := "button",
          child.text <-- options.signal.map(o =>
            if o.doubleIndent then "Use 2-space indentation" else "Use 4-space indentation"
          ),
          onClick --> { _ => options.update(o => o.copy(doubleIndent = !o.doubleIndent)) }
        )
      ),
      div(
        cls := "output-tabs",
        button(
          cls := "formatted-tab",
          tpe := "button",
          "Formatted",
          aria.pressed <-- resolved.signal.map(r => (!r).toString),
          onClick.mapTo(false) --> resolved
        ),
        button(
          cls := "resolved-tab",
          tpe := "button",
          "Resolved",
          aria.pressed <-- resolved.signal.map(_.toString),
          onClick.mapTo(true) --> resolved
        )
      ),
      p(
        cls := "resolution-note",
        hidden <-- resolved.signal.map(!_),
        "Local preview only. Includes are not loaded; environment variables (including PORT) are unset. Required substitutions must be defined in this text."
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
            onInput.mapToValue --> input,
            height <-- paneHeights,
            syncedPane(paneHeight)
          )
        ),
        label(
          cls := "pane",
          span(cls := "pane-title", child.text <-- resolved.signal.map(r => if r then "Resolved" else "Formatted")),
          textArea(
            cls      := "conf",
            readOnly := true,
            value <-- verdict
              .combineWith(options.signal)
              .combineWith(resolved.signal)
              .map { (text: String, decision: Verdict, chosen: FormatOptions, resolve: Boolean) =>
                val entry = text -> decision
                if resolve then resolvedOutput(entry, chosen) else out(entry)
              },
            height <-- paneHeights,
            syncedPane(paneHeight)
          )
        )
      ),
      p(cls := "status", aria.live := "polite", child <-- verdict.map(statusLine)),
      div(
        cls       := "findings",
        aria.live := "polite",
        children <-- settled.map { text =>
          DuplicateReport
            .findings(text, "playground")
            .fold(
              _ => Nil,
              _.map { case Finding.KeyDefinedAgain(path, earlier, later) =>
                p(s"${path.rendered}: line $earlier has no effect; replaced at line $later.")
              }
            )
        }
      )
    )
  }

  private def exampleButton(example: eu.ww86.hoconfmt.Example, input: Var[String]): HtmlElement =
    button(
      cls   := "example",
      tpe   := "button",
      title := example.shows,
      example.title,
      onClick.mapTo(example.input) --> input
    )

  /** One viewport, twice: the reader drags either pane, and the other takes the same height. The
    * sync holds on the phone layout too — there the panes are stacked, so the equal heights do
    * not sit beside each other, but they still show the same number of lines of the input and of
    * its output as the reader scrolls between them, and a drag there is a deliberate request for
    * that many lines.
    *
    * The shared height stays empty until the first drag — the stylesheet's default rules until
    * then, so the phone layout keeps its own, smaller height. Each pane's starting height is read
    * once, at mount: the observer reporting that same height is the size the pane began with, not
    * a drag. Any other reported height that is not already the shared one is the reader's, and
    * the page writes it back to both panes; the other pane's observer hears that write as a new
    * size, but it equals the shared height, so the chain ends there. That equality — not a timer
    * or a guard flag — is what keeps the observer from feeding itself.
    */
  private def syncedPane(shared: Var[Option[Int]]): Modifier[HtmlElement] =
    onMountUnmountCallbackWithState[HtmlElement, dom.ResizeObserver](
      mount = { ctx =>
        val pane     = ctx.thisNode.ref
        val starting = pane.offsetHeight.toInt
        // The write waits for the next frame. Changing the other pane's box while the browser is
        // still delivering resize observations leaves that change undelivered for the frame,
        // which Chrome reports as a ResizeObserver loop; a drag already takes more than a frame.
        def sync(height: Int): Unit = {
          val _ = dom.window.requestAnimationFrame { _ =>
            if !shared.now().contains(height) then shared.set(Some(height))
          }
        }
        val observer = new dom.ResizeObserver((_, _) => {
          val height = pane.offsetHeight.toInt
          shared.now() match {
            case Some(current) => if height != current then sync(height)
            case None          => if height != starting then sync(height)
          }
        })
        observer.observe(pane)
        observer
      },
      unmount = (_, observer) => observer.foreach(_.disconnect())
    )

  /** One verdict per settled input; kept separate from the DOM for reactive tests. Typed text has
    * no file name behind it, so a refusal names the playground as the place a parse tripped.
    */
  private[site] def verdicts(input: Signal[String], first: String, debounceMs: Int = 150): Signal[(String, Verdict)] = {
    settledText(input, first, debounceMs).map(text => text -> Verdict.of(text, "playground"))
  }

  private def settledText(input: Signal[String], first: String, debounceMs: Int): Signal[String] =
    input.distinct.changes.debounce(debounceMs).startWith(first).distinct

  // UPSTREAM-SCONFIG: resolved trees still need the legacy ordering options until upstream
  // offers a non-deprecated origin-order render API; document formatting cannot recover that order.
  @scala.annotation.nowarn("cat=deprecation")
  private def resolvedRenderOptions: ConfigRenderOptions =
    ConfigRenderOptions.defaults
      .setJson(false)
      .setOriginComments(false)
      .setComments(false)
      .setConfigFormatOptions(
        ConfigFormatOptions.defaults
          .setDoubleIndent(true)
          .setKeepOriginOrder(true)
          .setNewLineAtEnd(true)
      )

  /** The include statements of `text` swapped in place for stand-in fields of the site's own,
    * with the statements keyed by their index. The resolving parse sconfig needs here cannot read
    * an include in the browser at all; the formatter's retired masking pass once did this job, and
    * this is the display's own reader, not a formatter mechanism: a user key spelled exactly like
    * a stand-in is dropped from this view alone.
    */
  private def maskIncludesForResolve(text: String): (String, Map[Int, String]) = {
    val out       = new StringBuilder
    val originals = Map.newBuilder[Int, String]
    val lastEnd   = IncludeStatements.locate(text).zipWithIndex.foldLeft(0) { (copied, withIndex) =>
      val (located, index) = withIndex
      val standIn          = s"__RESOLVED_INCLUDE_$index"
      val _                = out.append(text.substring(copied, located.keywordStart)).append(s"$standIn : \"$standIn\"")
      originals += index -> s"include ${located.target}"
      located.end
    }
    val _ = out.append(text.substring(lastEnd))
    (out.result(), originals.result())
  }

  /** Resolve only accepted text, removing the include statements without reading any files.
    * The browser has no process environment; disabling it also makes the preview reproducible.
    * What sconfig renders resolved is raw text again, so the style choices reach the resolved
    * view only through our renderer, the one the formatted pane shows.
    */
  private def resolvedOutput(entry: (String, Verdict), chosen: FormatOptions): String = entry match {
    case (text, Verdict.Refused(_)) => text
    case (text, _)                  =>
      Try {
        val (maskedText, statements) = maskIncludesForResolve(text)
        val generated                = statements.keys.map(index => s"__RESOLVED_INCLUDE_$index").toSet
        val config                   = ConfigFactory.parseString(maskedText)
        val local                    = config.entrySet.asScala
          .map(_.getKey)
          .filter(path => ConfigUtil.splitPath(path).asScala.lastOption.exists(generated.contains))
          .foldLeft(config)((acc, path) => acc.withoutPath(path))
        val rendered = local
          .resolve(ConfigResolveOptions.defaults.setUseSystemEnvironment(false))
          .root
          .render(resolvedRenderOptions)
        // Every value here survived the verdict's collision probe on the input, so our renderer
        // refusing its own accepted render is not a case to dress up: the untouched render stays
        // the truth about the values, and a refusal line would hide them behind one message.
        HoconFormatter.format(rendered, chosen).getOrElse(rendered)
      }.fold(e => s"Resolution unavailable: ${Option(e.getMessage).getOrElse(e.toString)}", identity)
  }

  /** The output pane shows the formatting; a refused or settled text stays exactly as typed. */
  private def out(entry: (String, Verdict)): String = entry match {
    case (_, Verdict.NeedsFormatting(formatted)) => formatted
    case (text, _)                               => text
  }

  private[site] def statusLine(entry: (String, Verdict)): HtmlElement = {
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
          " — " + explanation + " Your input is unchanged in both panes; no file is written.",
          span(cls := "refusal-detail", "Details: " + reason),
          learnMore.map(url =>
            a(href := url, target := "_blank", rel := "noopener noreferrer", "Why the formatter refuses this")
          )
        )
    }
  }
}
