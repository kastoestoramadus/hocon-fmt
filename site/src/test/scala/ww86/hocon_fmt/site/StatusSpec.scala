package ww86.hocon_fmt.site

import ww86.hocon_fmt.Verdict

class StatusSpec extends munit.FunSuite {

  test("a verdict that needs formatting reports how many lines differ") {
    val Verdict.NeedsFormatting(formatted) = Verdict.of("a  = 1"): @unchecked
    assertEquals(Status.of(Verdict.NeedsFormatting(formatted), "a  = 1"), Status.Formatted(1))
  }

  test("an already formatted verdict reports no change") {
    assertEquals(Status.of(Verdict.of("a: 1\n"), "a: 1\n"), Status.AlreadyFormatted)
  }

  test("lines are compared from the top, so a reordering counts each moved line once") {
    assertEquals(Status.changedLines("a\nb\nc", "c\nb\na"), 2)
    assertEquals(Status.changedLines("a\nb", "a\nb\nc\nd"), 2)
    assertEquals(Status.changedLines("a\nb\n", "a\nb"), 0)
    assertEquals(Status.changedLines("", ""), 0)
    assertEquals(Status.changedLines("a : [1]", "a: [1]"), 1)
  }

  test("a refusal leaves the text alone and says why, with the limitations section to read") {
    val Verdict.Refused(why)                                       = Verdict.of("server {\n    listen 80;\n}"): @unchecked
    val status                                                     = Status.of(Verdict.Refused(why), "server {\n    listen 80;\n}")
    val Status.LeftUnchanged(kind, reason, explanation, learnMore) = status: @unchecked
    assertEquals(kind, RefusalKind.NotHocon)
    assertEquals(reason, why.reason)
    assert(explanation.nonEmpty, "the sentence must say what happened")
    assertEquals(learnMore, Some(Status.limitationsPage))
  }

  test("every refusal kind carries a non-empty explanation, and library defects link the section") {
    val withSection = List(
      RefusalKind.BrokenOutput,
      RefusalKind.LostComment,
      RefusalKind.LostInclude,
      RefusalKind.MovedInclude,
      RefusalKind.UnstableOutput
    )
    withSection.foreach { kind =>
      val Status.LeftUnchanged(_, _, explanation, learnMore) = statusFor(kind): @unchecked
      assert(explanation.nonEmpty, s"$kind needs its sentence")
      assertEquals(learnMore, Some(Status.defectsSection), s"$kind must link the defects section")
    }
  }

  test("NotUtf8 cannot arise from a browser string but is still explained") {
    val Status.LeftUnchanged(_, _, explanation, learnMore) = statusFor(RefusalKind.NotUtf8): @unchecked
    assert(explanation.nonEmpty)
    assertEquals(learnMore, None)
  }

  private def statusFor(kind: RefusalKind): Status =
    Status.of(Verdict.Refused(refusalOf(kind)), "source")

  test("a file whose name promises another format is its own refusal, not NotHocon") {
    // sconfig reads .json and .properties fine, so "cannot read this text as HOCON" would be
    // false; the file is a format of its own that this formatter does not format.
    val Status.LeftUnchanged(kind, reason, explanation, learnMore) =
      statusFor(RefusalKind.OtherFormat): @unchecked
    assertEquals(kind, RefusalKind.OtherFormat)
    assert(reason.contains("JSON"), reason)
    assert(explanation.nonEmpty, "the sentence must say what happened")
    assertEquals(learnMore, Some(Status.limitationsPage))
  }

  private def refusalOf(kind: RefusalKind): ww86.hocon_fmt.Refusal = kind match {
    case RefusalKind.NotHocon       => ww86.hocon_fmt.Refusal.NotHocon("no")
    case RefusalKind.OtherFormat    => ww86.hocon_fmt.Refusal.OtherFormat("JSON")
    case RefusalKind.BrokenOutput   => ww86.hocon_fmt.Refusal.BrokenOutput("no")
    case RefusalKind.LostComment    => ww86.hocon_fmt.Refusal.LostComment("# gone")
    case RefusalKind.LostInclude    => ww86.hocon_fmt.Refusal.LostInclude("include \"x.conf\"")
    case RefusalKind.MovedInclude   => ww86.hocon_fmt.Refusal.MovedInclude("include \"x.conf\"")
    case RefusalKind.UnstableOutput => ww86.hocon_fmt.Refusal.UnstableOutput
    case RefusalKind.NotUtf8        => ww86.hocon_fmt.Refusal.NotUtf8
  }
}
