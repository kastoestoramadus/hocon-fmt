package ww86.hocon_fmt.site

import ww86.hocon_fmt.{Refusal, Verdict}

/** Why the formatter left a text alone, named for the page. A browser string cannot fail UTF-8
  * decoding, so `NotUtf8` exists here only to make the mapping total.
  */
enum RefusalKind:
  case NotHocon, BrokenOutput, LostComment, LostInclude, UnstableOutput, NotUtf8

object RefusalKind:
  def of(refusal: Refusal): RefusalKind = refusal match {
    case Refusal.NotUtf8         => RefusalKind.NotUtf8
    case Refusal.NotHocon(_)     => RefusalKind.NotHocon
    case Refusal.BrokenOutput(_) => RefusalKind.BrokenOutput
    case Refusal.LostComment(_)  => RefusalKind.LostComment
    case Refusal.LostInclude(_)  => RefusalKind.LostInclude
    case Refusal.UnstableOutput  => RefusalKind.UnstableOutput
  }

/** The one status line the playground shows under the output pane. */
enum Status:
  case Formatted(changedLines: Int)
  case AlreadyFormatted
  case LeftUnchanged(
      kind: RefusalKind,
      reason: String,
      explanation: String,
      learnMore: Option[String]
  )

object Status:

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
  def changedLines(before: String, after: String): Int =
    val beforeLines = before.linesIterator.toVector
    val afterLines  = after.linesIterator.toVector
    (0 until math.max(beforeLines.size, afterLines.size))
      .count(i => beforeLines.lift(i) != afterLines.lift(i))

  private def explanation(kind: RefusalKind): String = kind match {
    case RefusalKind.NotHocon     => "sconfig cannot read this text as HOCON, so there is nothing to format."
    case RefusalKind.BrokenOutput =>
      "the configuration library renders this as text it cannot read back, so the formatter leaves it alone rather than hand it on."
    case RefusalKind.LostComment =>
      "the configuration library would drop a comment, so the formatter leaves the file alone."
    case RefusalKind.LostInclude =>
      "the configuration library would drop an include directive, so the formatter leaves the file alone."
    case RefusalKind.UnstableOutput => "the output would not settle: formatting it again would change it again."
    case RefusalKind.NotUtf8        => "the bytes are not valid UTF-8."
  }

  private def learnMore(kind: RefusalKind): Option[String] = kind match {
    case RefusalKind.NotUtf8                                                                                       => None
    case RefusalKind.NotHocon                                                                                      => Some(limitationsPage)
    case RefusalKind.BrokenOutput | RefusalKind.LostComment | RefusalKind.LostInclude | RefusalKind.UnstableOutput =>
      Some(defectsSection)
  }
