package ww86.hocon_fmt.web

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}

import ww86.hocon_fmt.{Refusal, Verdict}

/** What `HoconFormatter.format` hands a page: a plain object, [[Verdict]] with JavaScript names.
  * `formatted` is set unless the verdict is `refused`; `refusal` and `reason` only when it is.
  */
trait FormatResult extends js.Object {
  val verdict: String
  val formatted: js.UndefOr[String] = js.undefined
  val refusal: js.UndefOr[String]   = js.undefined
  val reason: js.UndefOr[String]    = js.undefined
}

/** The one global the script defines. */
@JSExportTopLevel("HoconFormatter")
object HoconFormatterJs {

  /** Text a page hands over has no file name behind it, so a refusal names the playground as the
    * place the parse tripped rather than saying "String".
    */
  @JSExport
  def format(source: String): FormatResult = Verdict.of(source, "playground") match {
    case Verdict.AlreadyFormatted =>
      new FormatResult {
        val verdict            = "alreadyFormatted"
        override val formatted = source
      }
    case Verdict.NeedsFormatting(text) =>
      new FormatResult {
        val verdict            = "needsFormatting"
        override val formatted = text
      }
    case Verdict.Refused(why) =>
      new FormatResult {
        val verdict          = "refused"
        override val refusal = nameOf(why)
        override val reason  = why.reason
      }
  }

  @JSExport
  val version: String = BuildInfo.version

  /** Part of the API: a page may explain each refusal in its own words. */
  def nameOf(refusal: Refusal): String = refusal match {
    case Refusal.NotUtf8         => "notUtf8"
    case Refusal.NotHocon(_)     => "notHocon"
    case Refusal.OtherFormat(_)  => "otherFormat"
    case Refusal.BrokenOutput(_) => "brokenOutput"
    case Refusal.LostComment(_)  => "lostComment"
    case Refusal.LostInclude(_)  => "lostInclude"
    case Refusal.MovedInclude(_) => "movedInclude"
    case Refusal.ReservedName    => "reservedName"
    case Refusal.UnstableOutput  => "unstableOutput"
  }
}
