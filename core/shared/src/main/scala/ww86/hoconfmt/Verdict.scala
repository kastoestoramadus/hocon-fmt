package ww86.hoconfmt

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8

/** What formatting makes of one file's content, decided without touching the file.
  *
  * Every integration acts on this, so the CLI and the build-tool plugins cannot disagree about a
  * file: they differ only in how they find files and report.
  */
enum Verdict derives CanEqual {
  case AlreadyFormatted
  case NeedsFormatting(formatted: String)
  case Refused(refusal: Refusal)
}

object Verdict {

  def of(source: String): Verdict =
    decide(source, None, FormatOptions.default)

  /** As [[of(String)]], with `name` the name the caller knows the text by: a name promising
    * another format rules the text out, and a parse failure reports it as the origin.
    */
  def of(source: String, name: String): Verdict =
    decide(source, Some(name), FormatOptions.default)

  /** As [[of(String)]], with the style options the caller asks for. */
  def of(source: String, options: FormatOptions): Verdict =
    decide(source, None, options)

  /** As [[of(String, String)]], with the style options the caller asks for. */
  def of(source: String, name: String, options: FormatOptions): Verdict =
    decide(source, Some(name), options)

  def of(content: Array[Byte]): Verdict =
    decide(content, None, FormatOptions.default)

  /** As [[of(Array[Byte])]], with `name` the name the caller knows the file by. */
  def of(content: Array[Byte], name: String): Verdict =
    decide(content, Some(name), FormatOptions.default)

  /** As [[of(Array[Byte])]], with the style options the caller asks for. */
  def of(content: Array[Byte], options: FormatOptions): Verdict =
    decide(content, None, options)

  /** As [[of(Array[Byte], String)]], with the style options the caller asks for. */
  def of(content: Array[Byte], name: String, options: FormatOptions): Verdict =
    decide(content, Some(name), options)

  // The name decides a format of its own before anything is read: whatever the content, this
  // formatter would hand it back as HOCON, which is not what the name promises.
  private def decide(content: Array[Byte], name: Option[String], options: FormatOptions): Verdict =
    namedFormat(name) match {
      case Some(refusal) => Refused(refusal)
      case None          => decodeUtf8(content).fold(Refused(_), decide(_, name, options))
    }

  private def decide(source: String, name: Option[String], options: FormatOptions): Verdict =
    namedFormat(name) match {
      case Some(refusal) => Refused(refusal)
      case None          =>
        val formatted = name match {
          case Some(origin) => HoconFormatter.format(source, origin, options)
          case None         => HoconFormatter.format(source, options)
        }
        formatted match {
          case Left(refusal)                 => Refused(refusal)
          case Right(text) if text == source => AlreadyFormatted
          case Right(text)                   => NeedsFormatting(text)
        }
    }

  // The formats Lightbend's loader also picks by extension. A round trip would hand back HOCON:
  // a `.json` file's objects reordered, a `.properties` value such as a JDBC URL not even read.
  private val OtherFormats = List(".json" -> "JSON", ".properties" -> "Java properties")

  private def namedFormat(name: Option[String]): Option[Refusal] =
    name.flatMap { fileName =>
      val dot       = fileName.lastIndexOf('.')
      val extension = if (dot < 0) "" else fileName.substring(dot)
      OtherFormats.collectFirst {
        case (known, format) if known.equalsIgnoreCase(extension) => Refusal.OtherFormat(format)
      }
    }

  // The default decoder reports malformed input instead of replacing it.
  private def decodeUtf8(content: Array[Byte]): Either[Refusal, String] =
    try Right(UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString)
    catch { case _: CharacterCodingException => Left(Refusal.NotUtf8) }
}
