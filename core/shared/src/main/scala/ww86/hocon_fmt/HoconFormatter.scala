package ww86.hocon_fmt

import org.ekrich.config.*
import scala.util.Try

/** Formats HOCON text.
  *
  * Everything except `include` handling is sconfig's job: this parses, re-renders, and puts the
  * include statements back. When output comes out wrong for any other reason the bug is upstream,
  * and the response here is to refuse the file rather than work around it — see [[Refusal]] and
  * `SconfigDefectsSpec`.
  */
object HoconFormatter {

  // Visible to the tests: a copy over there would drift, and a library test rendering with
  // options other than these would prove nothing about this formatter.
  private[hocon_fmt] val parseOptions = CommentCarrier.parseOptions(ConfigParseOptions.defaults.setAllowMissing(true))

  private val formattingOptions = ConfigFormatOptions.defaults
    .setKeepOriginOrder(true)
    .setDoubleIndent(false)
    .setColonAssign(true)
    .setSimplifyNestedObjects(true)
    // The final newline is an upstream default today; set here so a default moving under us
    // cannot change the output — sconfig#566 once removed FormattingOptions from under its users.
    .setNewLineAtEnd(true)

  private[hocon_fmt] val renderOptions = ConfigRenderOptions.defaults
    .setJson(false)
    .setOriginComments(false)
    .setComments(true)
    .setFormatted(true)
    // Upstream's default too, and invisible while origin comments are off; set for the same
    // reason as the newline.
    .setShowEnvVariableValues(true)
    .setConfigFormatOptions(formattingOptions)

  /** Formatted text, or why the input was left alone. */
  def format(source: String): Either[Refusal, String] =
    formatWith(source, parseOptions)

  /** As [[format(String)]], with `origin` naming where the text came from, so a refusal reports
    * `conf/application.conf: 8: ...` rather than `String: 8: ...`.
    */
  def format(source: String, origin: String): Either[Refusal, String] =
    formatWith(source, parseOptions.setOriginDescription(origin))

  private def formatWith(source: String, options: ConfigParseOptions): Either[Refusal, String] =
    for {
      pass <- formatOnce(source, options)(Refusal.NotHocon(_))
      _    <- commentsKept(source, pass.text)
      _    <- secondPassAgrees(pass.text, options)
      _    <- includesKeptInPlace(source, pass)
    } yield pass.text

  /** What one round trip produced: the text with the includes put back, and the masked text it
    * came from, which names the includes by the index the source gave them.
    */
  private case class Pass(text: String, masked: String, originals: Map[Int, String])

  /** One parse-render round trip with the includes carried across, kept separate from [[format]]
    * so the check there can run another pass without recursing back through it. `unreadable`
    * names the refusal for text sconfig cannot parse: the input's fault on the first pass, the
    * formatter's on the second.
    */
  private def formatOnce(source: String, options: ConfigParseOptions)(
      unreadable: String => Refusal
  ): Either[Refusal, Pass] = {
    val masked = IncludeMasking.mask(source)
    val carried = CommentCarrier.mask(masked.text)
    for {
      rendered <- attempt(render(carried.text, options))(unreadable)
      _        <- IncludeMasking.lost(rendered, masked.originals).headOption.map(Refusal.LostInclude(_)).toLeft(())
    } yield {
      // The masked text with its carried parts back, so nothing downstream sees our placeholders.
      val restored = carried.restore(rendered)
      Pass(IncludeMasking.unmask(restored, masked.originals), restored, masked.originals)
    }
  }

  private def render(masked: String, options: ConfigParseOptions): String = {
    val parsed = ConfigFactory.parseString(masked, options)
    if (parsed.isEmpty) "" // rendering an empty root would produce "{}"
    else parsed.root.render(renderOptions)
  }

  /** Formatting the output again is the whole check. The CLI writes on success, so output that
    * will not parse again would replace a valid config with a broken one; and a formatter that is
    * not a fixed point keeps producing diffs on unchanged files. Parsing alone would not do:
    * sconfig renders an unresolved merge as a comment banner that parses but grows on every pass.
    *
    * The pass parses the masked form. The include statements in the output are the ones just put
    * back verbatim, and resolving them would reach for the filesystem, which says nothing about
    * whether the text is well formed and which sconfig cannot do at all on Scala.js.
    */
  private def secondPassAgrees(formatted: String, options: ConfigParseOptions): Either[Refusal, Unit] =
    formatOnce(formatted, options)(Refusal.BrokenOutput(_))
      .filterOrElse(_.text == formatted, Refusal.UnstableOutput)
      .map(_ => ())

  /** Judged last, so a file sconfig mis-renders is refused for that and not for where the includes
    * ended up in text nobody will read. Only the first pass is compared with the source: the second
    * is the output itself, and agreeing with it was just checked.
    */
  private def includesKeptInPlace(source: String, pass: Pass): Either[Refusal, Unit] =
    IncludeOrder.moved(source, pass.masked, pass.originals).map(Refusal.MovedInclude(_)).toLeft(())

  /** Every comment of the source, as many times as it occurs; the multiset difference names the
    * first one missing.
    */
  private def commentsKept(source: String, formatted: String): Either[Refusal, Unit] =
    HoconText.comments(source).diff(HoconText.comments(formatted)).headOption.map(Refusal.LostComment(_)).toLeft(())

  private def attempt[A](run: => A)(refusal: String => Refusal): Either[Refusal, A] =
    Try(run).toEither.left.map(e => refusal(Option(e.getMessage).getOrElse(e.toString)))
}
