package eu.ww86.hoconfmt

import org.ekrich.config.*
import scala.annotation.tailrec
import scala.util.Try

/** Formats HOCON text.
  *
  * Everything except `include` handling is sconfig's job: this parses, re-renders, and puts the
  * include statements back. When output comes out wrong for any other reason the bug is upstream,
  * and the response here is to refuse the file rather than work around it — see [[Refusal]] and
  * `SconfigDefectsSpec`.
  */
/** Pipeline snapshot: only the include checks use the frozen implementations. */
object IncludeChecksOldFormatter {

  // Visible to the tests: a copy over there would drift, and a library test rendering with
  // options other than these would prove nothing about this formatter.
  private[hoconfmt] val parseOptions = CommentCarrier.parseOptions(ConfigParseOptions.defaults.setAllowMissing(true))

  def formattingOptions(options: FormatOptions): ConfigFormatOptions =
    ConfigFormatOptions.defaults
      .setKeepOriginOrder(true)
      .setDoubleIndent(options.doubleIndent)
      // The separator travels as colonAssign, the one switch sconfig has for the two tokens; `=`
      // is asked for by leaving it off.
      .setColonAssign(options.separator == Separator.Colon)
      .setSimplifyNestedObjects(options.simplifyNestedObjects)
      // The final newline is an upstream default today; set here so a default moving under us
      // cannot change the output — sconfig#566 once removed FormattingOptions from under its users.
      .setNewLineAtEnd(true)

  private[hoconfmt] def renderOptions(options: FormatOptions): ConfigRenderOptions =
    ConfigRenderOptions.defaults
      .setJson(false)
      .setOriginComments(false)
      .setComments(true)
      .setFormatted(true)
      // Upstream's default too, and invisible while origin comments are off; set for the same
      // reason as the newline.
      .setShowEnvVariableValues(true)
      .setConfigFormatOptions(formattingOptions(options))

  private[hoconfmt] def renderOptions: ConfigRenderOptions = renderOptions(FormatOptions.default)

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

  def formatWith(source: String, origin: Option[String], options: FormatOptions): Either[Refusal, String] =
    try HoconText.checkNesting(source).flatMap(_ => formatChecked(source, origin, options))
    catch {
      case _: StackOverflowError => Left(stackCapacityRefusal)
    }

  def formatChecked(source: String, origin: Option[String], options: FormatOptions): Either[Refusal, String] = {
    val parse = origin.fold(parseOptions)(parseOptions.setOriginDescription)
    val read  = reading(source)
    for {
      pass <- formatOnce(read, parse, options, probe = true)(Refusal.NotHocon(_))
      text  = read.putBack(pass.text)
      _    <- secondPassAgrees(text, parse, options)
      _    <- commentsKept(source, text)
      _    <- includesKeptInPlace(source, pass)
      _    <- definitionsKept(pass)
    } yield text
  }

  /** The source as the pipeline reads it: `source` is the file's own text, which the source-level
    * checks read; `parsed` is what the parse and the include checks read; `putBack` returns the
    * file's text with the formatted body. The two differ only through a preserved preamble.
    */
  case class Reading(source: String, parsed: String, preamble: String) {
    def putBack(body: String): String = preamble + body
  }

  /** A leading block sconfig would drop is taken verbatim and replaced, line for line, by blank
    * lines: comments and blank lines are both non-tokens, so the body parses to the tree and the
    * origin lines the file itself would give, and a refusal from the body names the file's lines.
    *
    * Where the parse already keeps detached comments (the page's fork), the block survives the
    * ordinary path through the carrier instead, and this path stays out of its way: one mechanism
    * owns the block, never both.
    */
  def reading(source: String): Reading =
    if (CommentCarrier.keepsDetachedComments) Reading(source, source, "")
    else
      Preamble.split(source) match {
        case Some(split) => Reading(source, split.blankLines + split.body, split.preamble)
        case None        => Reading(source, source, "")
      }

  /** What one round trip produced: the text with the includes put back, the masked text it came
    * from, which names the includes by the index the source gave them, and the parse of that
    * masked text, whose origins say which lines survived the merge.
    */
  case class Pass(
      text: String,
      masked: String,
      originals: Map[Int, String],
      parsedText: String,
      root: ConfigObject
  )

  /** One parse-render round trip with the includes carried across, kept separate from [[format]]
    * so the check there can run another pass without recursing back through it. `unreadable`
    * names the refusal for text sconfig cannot parse: the input's fault on the first pass, the
    * formatter's on the second. `probe` asks for the collision probe, which only the first pass
    * needs: its input is the user's text, where a mimic of a placeholder can hide behind an
    * identical generated field.
    *
    * The parse reads `reading.parsed`; the source-level guard reads the file's own text, so a
    * reserved spelling in a preserved preamble is refused exactly as it was before the path
    * existed. Nothing of the preamble is parsed or restored, so it can never be mistaken for a
    * placeholder — keeping the guard whole-file is the conservative reading, not a necessity.
    */
  def formatOnce(reading: Reading, parse: ConfigParseOptions, options: FormatOptions, probe: Boolean)(
      unreadable: String => Refusal
  ): Either[Refusal, Pass] = {
    val masked  = IncludeMasking.mask(reading.parsed)
    val written =
      if (reading.preamble.isEmpty) masked else IncludeMasking.mask(reading.source)
    // UPSTREAM-SCONFIG: the seam call; inline `restore`'s absence once the option is released.
    val carried = CommentCarrier.mask(masked.text)
    for {
      _         <- Either.cond(!IncludeMasking.collides(written), (), Refusal.ReservedName)
      parsed    <- attempt(ConfigFactory.parseString(carried.text, parse))(unreadable)
      collision <- attempt(IncludeMasking.collides(masked, parsed.root))(unreadable)
      _         <- Either.cond(!collision, (), Refusal.ReservedName)
      reserved  <- attempt(carried.collides(parsed.root))(unreadable)
      _         <- Either.cond(!reserved, (), Refusal.ReservedName)
      rendered  <- attempt(if (parsed.isEmpty) "" else parsed.root.render(renderOptions(options)))(unreadable)
      _         <- if (!probe || masked.originals.isEmpty) Right(())
           else {
             val probeText = IncludeMasking.collisionProbe(reading.parsed, rendered)
             attempt(ConfigFactory.parseString(probeText.text, parse))(unreadable).flatMap { checked =>
               attempt(IncludeMasking.probeCollides(checked.root))(unreadable).flatMap { collision =>
                 Either.cond(!collision, (), Refusal.ReservedName)
               }
             }
           }
      _ <- IncludeMasking.lost(rendered, masked.originals).headOption.map(Refusal.LostInclude(_)).toLeft(())
      _ <- Either.cond(!IncludeMasking.collides(masked, rendered), (), Refusal.ReservedName)
    } yield {
      // The masked text with its carried parts back, so nothing downstream sees our placeholders.
      val restored = carried.restore(rendered)
      Pass(
        IncludeMasking.unmask(restored, masked.originals),
        restored,
        masked.originals,
        carried.text,
        parsed.root
      )
    }
  }

  /** Checking UTF-8 encodability and formatting the output again protects the written file. Output that
    * will not parse again would replace a valid config with a broken one; and a formatter that is
    * not a fixed point keeps producing diffs on unchanged files. A raw unpaired surrogate parses
    * in a String but cannot survive UTF-8 encoding, so check the restored output before masking it.
    * Parsing alone would not do:
    * sconfig renders an unresolved merge as a comment banner that parses but grows on every pass.
    *
    * The pass parses the masked form. The include statements in the output are the ones just put
    * back verbatim, and resolving them would reach for the filesystem, which says nothing about
    * whether the text is well formed and which sconfig cannot do at all on Scala.js.
    *
    * No collision probe here: the probe hunts user text mimicking a placeholder, and this pass's
    * input is our own output, whose `__INCLUDE_` sits only inside the restored include statements,
    * which masking removes. A mimic the first pass could not see is the one its probe already
    * refused, and the probe would cost this pass a fourth parse of every include-bearing file.
    */
  def secondPassAgrees(
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
      _ <- formatOnce(read, parse, options, probe = false)(Refusal.BrokenOutput(_))
             .filterOrElse(pass => read.putBack(pass.text) == formatted, Refusal.UnstableOutput)
    } yield ()
  }

  // A UTF-16 half has no UTF-8 encoding on its own. Scan without allocating a byte copy on
  // every formatting pass, and leave escaping to sconfig rather than repair its rendering.
  def utf8Encodable(text: String): Boolean = {
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
    */
  def includesKeptInPlace(source: String, pass: Pass): Either[Refusal, Unit] =
    IncludeOrderOracle.moved(source, pass.masked, pass.originals).map(Refusal.MovedInclude(_)).toLeft(())

  /** Judged after the includes' places, so a file that would keep an include in place is refused
    * for the definition it drops rather than the other way around. Both mean the same to the user:
    * the file resolves to other values after formatting.
    */
  def definitionsKept(pass: Pass): Either[Refusal, Unit] =
    IncludeShadowOracle.shadowed(pass.parsedText, pass.originals, pass.root).toLeft(())

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
  def commentsKept(source: String, formatted: String): Either[Refusal, Unit] = {
    val before = HoconText.comments(source)
    val after  = HoconText.comments(formatted)
    before.diff(after).headOption.orElse(after.diff(before).headOption).map(Refusal.LostComment(_)).toLeft(())
  }

  def attempt[A](run: => A)(refusal: String => Refusal): Either[Refusal, A] =
    Try(run).toEither.left.map(e => refusal(Option(e.getMessage).getOrElse(e.toString)))
}
