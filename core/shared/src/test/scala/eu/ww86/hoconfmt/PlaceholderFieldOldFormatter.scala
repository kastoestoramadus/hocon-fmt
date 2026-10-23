package eu.ww86.hoconfmt

import org.ekrich.config.*
import scala.annotation.tailrec
import scala.util.Try

/** Pre-change formatter pipeline, with the old scan in both formatting passes. */
object PlaceholderFieldOldFormatter {

  val parseOptions = CommentCarrier.parseOptions(ConfigParseOptions.defaults.setAllowMissing(true))

  // Upstream deprecated carrying format options through the render path, so the frozen pipeline
  // renders with the render path's own formatting defaults; the non-formatting pins stay.
  def renderOptions: ConfigRenderOptions =
    ConfigRenderOptions.defaults
      .setJson(false)
      .setOriginComments(false)
      .setComments(true)
      .setFormatted(true)
      .setShowEnvVariableValues(true)

  def format(source: String): Either[Refusal, String] =
    formatWith(source, None, FormatOptions.default)

  def format(source: String, origin: String): Either[Refusal, String] =
    formatWith(source, Some(origin), FormatOptions.default)

  def format(source: String, options: FormatOptions): Either[Refusal, String] =
    formatWith(source, None, options)

  def format(source: String, origin: String, options: FormatOptions): Either[Refusal, String] =
    formatWith(source, Some(origin), options)

  def stackCapacityRefusal: Refusal =
    Refusal.NotHocon("input nesting exceeds available stack capacity")

  def formatWith(source: String, origin: Option[String], options: FormatOptions): Either[Refusal, String] =
    try formatChecked(source, origin, options)
    catch {
      case _: StackOverflowError => Left(stackCapacityRefusal)
    }

  def formatChecked(source: String, origin: Option[String], options: FormatOptions): Either[Refusal, String] = {
    // options stay in the signature for the callers; the render path they configured is deprecated
    // upstream, so the frozen pipeline renders with its own defaults.
    val _     = options
    val parse = origin.fold(parseOptions)(parseOptions.setOriginDescription)
    val read  = reading(source)
    for {
      pass <- formatOnce(read, parse, probe = true)(Refusal.NotHocon(_))
      text  = read.putBack(pass.text)
      _    <- secondPassAgrees(text, parse)
      _    <- commentsKept(source, text)
      _    <- includesKeptInPlace(source, pass)
      _    <- definitionsKept(pass)
    } yield text
  }

  case class Reading(source: String, parsed: String, preamble: String) {
    def putBack(body: String): String = preamble + body
  }

  def reading(source: String): Reading =
    if (CommentCarrier.keepsDetachedComments) Reading(source, source, "")
    else
      Preamble.split(source) match {
        case Some(split) => Reading(source, split.blankLines + split.body, split.preamble)
        case None        => Reading(source, source, "")
      }

  case class Pass(
      text: String,
      masked: String,
      originals: Map[Int, String],
      parsedText: String,
      root: ConfigObject
  )

  def formatOnce(reading: Reading, parse: ConfigParseOptions, probe: Boolean)(
      unreadable: String => Refusal
  ): Either[Refusal, Pass] = {
    val masked  = IncludeMasking.mask(reading.parsed)
    val written =
      if (reading.preamble.isEmpty) masked else IncludeMasking.mask(reading.source)

    val carried = CommentCarrier.mask(masked.text)
    for {
      _         <- Either.cond(!IncludeMasking.collides(written), (), Refusal.ReservedName)
      parsed    <- attempt(ConfigFactory.parseString(carried.text, parse))(unreadable)
      collision <- attempt(IncludeMasking.collides(masked, parsed.root))(unreadable)
      _         <- Either.cond(!collision, (), Refusal.ReservedName)
      reserved  <- attempt(carried.collides(parsed.root))(unreadable)
      _         <- Either.cond(!reserved, (), Refusal.ReservedName)
      rendered  <- attempt(if (parsed.isEmpty) "" else parsed.root.render(renderOptions))(unreadable)
      _         <- if (!probe || masked.originals.isEmpty) Right(())
           else {
             val probeText = IncludeMasking.collisionProbe(reading.parsed, rendered)
             attempt(ConfigFactory.parseString(probeText.text, parse))(unreadable).flatMap { checked =>
               attempt(IncludeMasking.probeCollides(checked.root))(unreadable).flatMap { collision =>
                 Either.cond(!collision, (), Refusal.ReservedName)
               }
             }
           }
      _ <- PlaceholderFieldOracle.lost(rendered, masked.originals).headOption.map(Refusal.LostInclude(_)).toLeft(())
      _ <- Either.cond(!PlaceholderFieldOracle.collides(masked, rendered), (), Refusal.ReservedName)
    } yield {

      val restored = carried.restore(rendered)
      Pass(
        PlaceholderFieldOracle.unmask(restored, masked.originals),
        restored,
        masked.originals,
        carried.text,
        parsed.root
      )
    }
  }

  def secondPassAgrees(formatted: String, parse: ConfigParseOptions): Either[Refusal, Unit] = {
    val read = reading(formatted)
    for {
      _ <- Either.cond(
             utf8Encodable(formatted),
             (),
             Refusal.BrokenOutput("an unpaired surrogate cannot be encoded as UTF-8")
           )
      _ <- formatOnce(read, parse, probe = false)(Refusal.BrokenOutput(_))
             .filterOrElse(pass => read.putBack(pass.text) == formatted, Refusal.UnstableOutput)
    } yield ()
  }

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

  def includesKeptInPlace(source: String, pass: Pass): Either[Refusal, Unit] =
    IncludeOrder.moved(source, pass.masked, pass.originals).map(Refusal.MovedInclude(_)).toLeft(())

  def definitionsKept(pass: Pass): Either[Refusal, Unit] =
    IncludeShadow.shadowed(pass.parsedText, pass.originals, pass.root).toLeft(())

  def commentsKept(source: String, formatted: String): Either[Refusal, Unit] = {
    val before = HoconText.comments(source)
    val after  = HoconText.comments(formatted)
    before.diff(after).headOption.orElse(after.diff(before).headOption).map(Refusal.LostComment(_)).toLeft(())
  }

  def attempt[A](run: => A)(refusal: String => Refusal): Either[Refusal, A] =
    Try(run).toEither.left.map(e => refusal(Option(e.getMessage).getOrElse(e.toString)))
}
