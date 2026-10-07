package ww86.hocon_fmt.site

import ww86.hocon_fmt.{Refusal, Verdict}

/** Pins what each example shows, through the core, on Scala.js — where an `include` never
  * reaches sconfig directly and the masking does the work. If an example stops showing what its
  * label promises, this suite is where it turns red.
  */
class ExamplesSpec extends munit.FunSuite:

  test("the messy example needs formatting and tidies the nested object") {
    assertContains(formatted(Examples.messy.source), "name: svc")
    assertContains(formatted(Examples.messy.source), "db.url: \"jdbc:postgresql://localhost/app\"")
  }

  test("the include example needs formatting and keeps the include") {
    assertContains(formatted(Examples.includes.source), "include \"local.conf\"")
  }

  test("the comments example needs formatting and keeps every comment") {
    val out = formatted(Examples.comments.source)
    assertContains(out, "# service defaults")
    assertContains(out, "# the service name")
    assertContains(out, "# said twice, never enough")
    assertContains(out, "# the db runs where you least expect it")
  }

  test("the nginx example is refused as not HOCON") {
    Verdict.of(Examples.notHocon.source) match {
      case Verdict.Refused(Refusal.NotHocon(_)) => ()
      case other                                => fail(s"expected Refused(NotHocon), got $other")
    }
  }

  test("the sconfig-defect example is refused because the output would not parse again") {
    Verdict.of(Examples.sconfigDefect.source) match {
      case Verdict.Refused(Refusal.BrokenOutput(_)) => ()
      case other                                    => fail(s"expected Refused(BrokenOutput), got $other")
    }
  }

  private def formatted(source: String): String = Verdict.of(source) match {
    case Verdict.NeedsFormatting(formatted) => formatted
    case other                              => fail(s"expected NeedsFormatting, got $other")
  }

  private def assertContains(text: String, part: String): Unit =
    assert(text.contains(part), s"expected the output to contain [$part], was:\n$text")
