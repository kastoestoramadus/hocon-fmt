package eu.ww86.hoconfmt

import org.ekrich.config.*
import org.ekrich.config.parser.ConfigDocumentFactory
import scala.annotation.tailrec
import scala.util.Try

/** Formats HOCON text.
  *
  * Everything except `include` handling is sconfig's job: this hands the text to the document
  * engine (`ConfigDocumentFactory.parseString(...).format(...).render`), which keeps the file as
  * written — duplicate definitions, `+=`, substitutions, numeric and quoted spelling, comments and
  * include statements — and never loads an include. When output comes out wrong for any other
  * reason the bug is upstream, and the response here is to refuse the file rather than work around
  * it — see [[Refusal]] and `SconfigDefectsSpec`.
  */
object HoconFormatter {

  // Visible to the tests: a copy over there would drift, and a library test rendering with
  // options other than these would prove nothing about this formatter.
  private[hoconfmt] val parseOptions = ConfigParseOptions.defaults.setAllowMissing(true)

  // The options the document engine applies are doubleIndent, colonAssign and
  // simplifyNestedObjects. Field order and the final newline are structural in it (the text always
  // ends with one newline, the order is always kept), and the setters for them are deprecated
  // no-ops there, so they are not set. The separator travels as colonAssign, the one switch
  // sconfig has for the two tokens; `=` is asked for by leaving it off. Upstream's
  // simplifyNestedObjects default is off while the formatter's is on, so it is passed explicitly.
  private def formattingOptions(options: FormatOptions): ConfigFormatOptions =
    ConfigFormatOptions.defaults
      .setDoubleIndent(options.doubleIndent)
      .setColonAssign(options.separator == Separator.Colon)
      .setSimplifyNestedObjects(options.simplifyNestedObjects)

  // The legacy `Config.render` path: the guards' differential tests and the site's resolved tab
  // still render a resolved tree. Upstream deprecated carrying format options through it ("Use
  // ConfigDocument.format to format config text"), so this pins only what was never formatting:
  // HOCON output, comments without origins, indent on. The render path's own formatting defaults
  // apply, and the format options travel through [[formattingOptions]] instead.
  private[hoconfmt] def renderOptions: ConfigRenderOptions =
    ConfigRenderOptions.defaults
      .setJson(false)
      .setOriginComments(false)
      .setComments(true)
      .setFormatted(true)
      // Upstream's default too, and invisible while origin comments are off; set for the same
      // reason as the newline.
      .setShowEnvVariableValues(true)

  /** Formatted text, or why the input was left alone. */
  def format(source: String): Either[Refusal, String] =
    formatWith(source, None, FormatOptions.default)

  /** As [[format(String)]], with `origin` naming where the text came from, so a refusal reports
    * `conf/application.conf: 8: ...` rather than `String: 8: ...`.
    */
  def format(source: String, origin: String): Either[Refusal, String] =
    formatWith(source, Some(origin), FormatOptions.default)

  /** As [[format(String)]], with the style options the caller asks for. */
  def format(source: String, options: FormatOptions): Either[Refusal, String] =
    formatWith(source, None, options)

  /** As [[format(String, String)]], with the style options the caller asks for. */
  def format(source: String, origin: String, options: FormatOptions): Either[Refusal, String] =
    formatWith(source, Some(origin), options)

  // sconfig's parser and renderer still recurse. Input beyond their capacity is unreadable
  // here; NonFatal (and Try) deliberately excludes this recoverable traversal failure.
  private[hoconfmt] def stackCapacityRefusal: Refusal =
    Refusal.NotHocon("input nesting exceeds available stack capacity")

  private def formatWith(source: String, origin: Option[String], options: FormatOptions): Either[Refusal, String] =
    formatInjecting(source, origin, options, identity)

  /** The seam the guard tests inject a losing render through: `tweak` rewrites the text the first
    * pass carries, after the engine's own survival checks have read the real render, and the
    * second pass renders without it. No reachable input loses a comment or an include — the
    * document engine keeps both verbatim, and the 13,272-case corpus sweep refused none — so
    * without this seam the guards in [[formatChecked]] could not be exercised at all.
    */
  private[hoconfmt] def formatInjecting(
      source: String,
      origin: Option[String],
      options: FormatOptions,
      tweak: String => String
  ): Either[Refusal, String] =
    try HoconText.checkNesting(source).flatMap(_ => formatChecked(source, origin, options, tweak))
    catch {
      case _: StackOverflowError => Left(stackCapacityRefusal)
    }

  private def formatChecked(
      source: String,
      origin: Option[String],
      options: FormatOptions,
      tweak: String => String
  ): Either[Refusal, String] = {
    val parse = origin.fold(parseOptions)(parseOptions.setOriginDescription)
    val read  = reading(source)
    for {
      pass <- formatOnce(read, parse, options, tweak)(Refusal.NotHocon(_))
      text  = read.putBack(pass.text)
      _    <- secondPassAgrees(text, parse, options)
      _    <- commentsKept(source, text)
      _    <- includesKeptInPlace(pass)
    } yield text
  }

  /** The source as the pipeline reads it: `source` is the file's own text, which the source-level
    * checks read; `parsed` is what the parse and the include checks read; `putBack` returns the
    * file's text with the formatted body. The two differ only through a preserved preamble.
    */
  private case class Reading(source: String, parsed: String, preamble: String) {
    def putBack(body: String): String = preamble + body
  }

  /** A leading block sconfig would drop is taken verbatim and replaced, line for line, by blank
    * lines: comments and blank lines are both non-tokens, so the body parses to the tree and the
    * origin lines the file itself would give, and a refusal from the body names the file's lines.
    *
    * The document engine runs on both variants; the wrapper preserves the same preamble bytes
    * even when the resolving parser can retain detached comments itself.
    */
  private def reading(source: String): Reading =
    Preamble.split(source) match {
      case Some(split) => Reading(source, split.blankLines + split.body, split.preamble)
      case None        => Reading(source, source, "")
    }

  /** What one round trip produced: the formatted text (the document engine writes include
    * statements itself, so nothing is put back) and the include statements of the source, in the
    * order the source gave them.
    */
  private case class Pass(text: String, statements: List[String])

  /** One document round trip, kept separate from [[format]] so the check there can run another
    * pass without recursing back through it. The formatted text comes from the document engine,
    * which keeps includes as document nodes and never loads one. No resolving parse stands behind
    * the formatting: the masking pass that once made the text parseable for one is gone, and no
    * part of the pipeline resolves includes. `unreadable` names the refusal for text sconfig
    * cannot parse: the input's fault on the first pass, the formatter's on the second.
    */
  private def formatOnce(
      reading: Reading,
      parse: ConfigParseOptions,
      options: FormatOptions,
      tweak: String => String = identity
  )(
      unreadable: String => Refusal
  ): Either[Refusal, Pass] = {
    val statements = IncludeStatements.statements(reading.parsed)
    for {
      document <- attempt(ConfigDocumentFactory.parseString(reading.parsed, parse))(unreadable)
      rendered <- attempt(
                    if (reading.parsed.trim.isEmpty) "" else document.format(formattingOptions(options)).render
                  )(unreadable)
      // The engine writes the include statements themselves, and the survival check reads them
      // back with the same locator that read the source's.
      _ <- includeStatementsPresent(rendered, statements)
    } yield Pass(tweak(rendered), statements)
  }

  // The engine normalises the spacing an include statement was written with, and nothing else
  // about one; the survival checks compare statements with runs of whitespace collapsed. The
  // pattern runs on RE2 and ES2015: no lookaround, no backreferences.
  private val collapsedWhitespace = """\s+""".r

  private def collapsed(text: String): String = collapsedWhitespace.replaceAllIn(text, " ").trim

  /** Every include statement the source gave survives in the engine's text, spelled the same up to
    * whitespace, as many times as it occurs: the check compares how often each statement occurs on
    * the two sides, so one of several identical includes cannot go missing behind the occurrences
    * that survive — a membership check would still find those. The refusal names the first
    * statement in source order whose count falls short. An occurrence the source did not give is
    * not this check's refusal: the count comparison of [[includesKeptInPlace]] refuses it before
    * the output is written. The engine writes includes itself, so the check reads the statements —
    * one locator scan, [[IncludeStatements]], shared with the source's own reading — not the
    * placeholders a resolving pipeline substituted.
    */
  // Visible to the tests, which pin this check at its seam: no injection reaches it, because it
  // reads the real render before the tweak a test injects runs.
  private[hoconfmt] def includeStatementsPresent(
      rendered: String,
      statements: List[String]
  ): Either[Refusal, Unit] = {
    val written = IncludeStatements.statements(rendered).map(collapsed)
    val wanted  = statements.map(statement => (statement, collapsed(statement)))
    val missing = wanted.find { case (_, key) => written.count(_ == key) < wanted.count { case (_, k) => k == key } }
    missing.map { case (statement, _) => Refusal.LostInclude(statement) }.toLeft(())
  }

  /** Checking UTF-8 encodability and formatting the output again protects the written file. Output that
    * will not parse again would replace a valid config with a broken one; and a formatter that is
    * not a fixed point keeps producing diffs on unchanged files. A raw unpaired surrogate parses
    * in a String but cannot survive UTF-8 encoding, so check the output before the second pass
    * reads it. Parsing alone would not do:
    * sconfig renders an unresolved merge as a comment banner that parses but grows on every pass.
    *
    * The include statements in the second pass's input are the ones the first pass's output
    * carries verbatim, and resolving them would reach for the filesystem, which says nothing
    * about whether the text is well formed and which sconfig cannot do at all on Scala.js.
    */
  private def secondPassAgrees(
      formatted: String,
      parse: ConfigParseOptions,
      options: FormatOptions
  ): Either[Refusal, Unit] = {
    val read = reading(formatted)
    for {
      _ <- Either.cond(
             utf8Encodable(formatted),
             (),
             Refusal.BrokenOutput("an unpaired surrogate cannot be encoded as UTF-8")
           )
      _ <- formatOnce(read, parse, options)(Refusal.BrokenOutput(_))
             .filterOrElse(pass => read.putBack(pass.text) == formatted, Refusal.UnstableOutput)
    } yield ()
  }

  // A UTF-16 half has no UTF-8 encoding on its own. Scan without allocating a byte copy on
  // every formatting pass, and leave escaping to sconfig rather than repair its rendering.
  private def utf8Encodable(text: String): Boolean = {
    @tailrec
    def pairedAt(index: Int): Boolean =
      if (index == text.length) true
      else {
        val character = text.charAt(index)
        if (Character.isHighSurrogate(character)) {
          index + 1 < text.length && Character.isLowSurrogate(text.charAt(index + 1)) && pairedAt(index + 2)
        } else if (Character.isLowSurrogate(character)) false
        else pairedAt(index + 1)
      }
    pairedAt(0)
  }

  /** Judged last, so a file sconfig mis-renders is refused for that and not for where the includes
    * ended up in text nobody will read. Only the first pass is compared with the source: the second
    * is the output itself, and agreeing with it was just checked.
    *
    * The document engine keeps the order of fields and includes structurally, so the check is the
    * order itself: the statements, whitespace collapsed, appear in the output as many times and in
    * the order the source gave them. The counts are compared first, statement by statement — a
    * `zip` alone would stop at the shorter side and read a lost or an extra occurrence as
    * agreement — and with every count equal the two sides are equally long, so the pairwise order
    * comparison reads every position on both. A count that differs is refused as a lost include,
    * whichever way it differs; an order that differs, as a moved one. Both sides come from
    * [[IncludeStatements]] — the same locator reads the source's statements and the output's.
    */
  private def includesKeptInPlace(pass: Pass): Either[Refusal, Unit] = {
    val written = IncludeStatements.statements(pass.text).map(collapsed)
    val wanted  = pass.statements.map(statement => (statement, collapsed(statement)))
    val refused = wanted
      .find { case (_, key) => written.count(_ == key) != wanted.count { case (_, k) => k == key } }
      .map { case (statement, _) => Refusal.LostInclude(statement) }
      .orElse(wanted.zip(written).collectFirst {
        case ((statement, key), writtenStatement) if key != writtenStatement => Refusal.MovedInclude(statement)
      })
    refused.toLeft(())
  }

  /** Every comment of the source, as many times as it occurs: the output must carry exactly the
    * multiset of the input, no comment dropped and none printed twice. A difference alone misses
    * the second kind — sconfig's `+=` desugaring copies the comment of the appended field onto
    * both halves of the expansion — and a comment nobody wrote is as much a change to the file as
    * a lost one. The refusal names the first comment in source order whose count differs, a loss
    * before a duplicate.
    *
    * Judged after the second pass, as the include's place is: a file sconfig mis-renders is refused
    * for that first. The rendering of an unresolved merge is a banner of comments nobody wrote,
    * which would otherwise be read here as a duplicate the author is not to blame for; the banner
    * also grows on every pass, so the second pass catches it, and the file is refused as output
    * that will not settle rather than as a comment changed.
    */
  private def commentsKept(source: String, formatted: String): Either[Refusal, Unit] = {
    val before = HoconText.comments(source)
    val after  = HoconText.comments(formatted)
    before.diff(after).headOption.orElse(after.diff(before).headOption).map(Refusal.LostComment(_)).toLeft(())
  }

  private def attempt[A](run: => A)(refusal: String => Refusal): Either[Refusal, A] =
    Try(run).toEither.left.map(e => refusal(Option(e.getMessage).getOrElse(e.toString)))
}
