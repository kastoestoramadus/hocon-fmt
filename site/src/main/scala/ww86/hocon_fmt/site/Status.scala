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

  def of(verdict: Verdict, source: String): Status = ???

  /** How many lines differ, comparing input and output line by line from the top. No diff
    * machinery: the status line needs a feel for the change, not a patch.
    */
  def changedLines(before: String, after: String): Int = ???

  private def explanation(kind: RefusalKind): String = ???

  private def learnMore(kind: RefusalKind): Option[String] = ???
