package eu.ww86.hoconfmt.site

import eu.ww86.hoconfmt.{Refusal, Verdict}

/** Why the formatter left a text alone, named for the page. A browser string cannot fail UTF-8
  * decoding, so `NotUtf8` exists here only to make the mapping total.
  */
enum RefusalKind derives CanEqual {

  case NotHocon, OtherFormat, BrokenOutput, LostComment, LostInclude, MovedInclude, ReservedName, UnstableOutput,
    NotUtf8

  /** The name the `web` script publishes in its JavaScript API, which `HoconFormatterJsSpec`
    * pins there; the defect table lists refusals by it, and `ContributionsSpec` pins the set.
    */
  def name: String = this match {
    case NotHocon       => "notHocon"
    case OtherFormat    => "otherFormat"
    case BrokenOutput   => "brokenOutput"
    case LostComment    => "lostComment"
    case LostInclude    => "lostInclude"
    case MovedInclude   => "movedInclude"
    case ReservedName   => "reservedName"
    case UnstableOutput => "unstableOutput"
    case NotUtf8        => "notUtf8"
  }
}

object RefusalKind {
  def of(refusal: Refusal): RefusalKind = refusal match {
    case Refusal.NotUtf8         => RefusalKind.NotUtf8
    case Refusal.NotHocon(_)     => RefusalKind.NotHocon
    case Refusal.OtherFormat(_)  => RefusalKind.OtherFormat
    case Refusal.BrokenOutput(_) => RefusalKind.BrokenOutput
    case Refusal.LostComment(_)  => RefusalKind.LostComment
    case Refusal.LostInclude(_)  => RefusalKind.LostInclude
    case Refusal.MovedInclude(_) => RefusalKind.MovedInclude
    case Refusal.ReservedName    => RefusalKind.ReservedName
    case Refusal.UnstableOutput  => RefusalKind.UnstableOutput
  }
}

/** The one status line the playground shows under the output pane. */
enum Status derives CanEqual {
  case Formatted(changedLines: Int)
  case AlreadyFormatted
  case LeftUnchanged(
      kind: RefusalKind,
      reason: String,
      explanation: String,
      learnMore: Option[String]
  )
}

object Status {

  /** Known limitations in the repository: where each refusal is described and reproduced. */
  val limitationsPage = "https://github.com/kastoestoramadus/hocon-fmt/blob/main/docs/limitations.md"
  val defectsSection  = s"$limitationsPage#sconfig-defects-the-formatter-refuses"

  def of(verdict: Verdict, source: String): Status = verdict match {
    case Verdict.AlreadyFormatted           => Status.AlreadyFormatted
    case Verdict.NeedsFormatting(formatted) => Status.Formatted(changedLines(source, formatted))
    case Verdict.Refused(refusal)           =>
      val kind = RefusalKind.of(refusal)
      Status.LeftUnchanged(kind, refusal.reason, explanation(kind), learnMore(kind))
  }

  /** How many lines differ, comparing input and output line by line from the top. No diff
    * machinery: the status line needs a feel for the change, not a patch.
    */
  def changedLines(before: String, after: String): Int = {
    val beforeLines = before.linesIterator.toVector
    val afterLines  = after.linesIterator.toVector
    (0 until math.max(beforeLines.size, afterLines.size))
      .count(i => beforeLines.lift(i) != afterLines.lift(i))
  }

  private def explanation(kind: RefusalKind): String = kind match {
    case RefusalKind.NotHocon    => "The parser cannot read this text as HOCON. Check the syntax at the location below."
    case RefusalKind.OtherFormat =>
      "The file name specifies another format. This formatter writes HOCON only."
    case RefusalKind.BrokenOutput =>
      "Formatting would produce text that the parser cannot read back."
    case RefusalKind.LostComment =>
      "Formatting would lose the comment shown below. This can happen when a later definition replaces a commented field, or when the library drops a detached comment."
    case RefusalKind.LostInclude  => "Formatting would drop an include directive."
    case RefusalKind.MovedInclude =>
      "Formatting would put an include on the other side of a field, so a later definition wins."
    case RefusalKind.ReservedName =>
      "The text uses __INCLUDE_, the name the formatter writes include placeholders with, so it cannot tell your text from its own."
    case RefusalKind.UnstableOutput => "Formatting would not settle: a second pass would change the output again."
    case RefusalKind.NotUtf8        => "The bytes are not valid UTF-8."
  }

  private def learnMore(kind: RefusalKind): Option[String] = kind match {
    case RefusalKind.NotUtf8                                                       => None
    case RefusalKind.NotHocon | RefusalKind.OtherFormat | RefusalKind.ReservedName => Some(limitationsPage)
    case RefusalKind.BrokenOutput | RefusalKind.LostComment | RefusalKind.LostInclude | RefusalKind.MovedInclude |
        RefusalKind.UnstableOutput =>
      Some(defectsSection)
  }
}
