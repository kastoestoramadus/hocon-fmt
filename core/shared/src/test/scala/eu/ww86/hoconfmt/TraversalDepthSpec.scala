package eu.ww86.hoconfmt

class TraversalDepthSpec extends munit.FunSuite {

  def nested(depth: Int): String = "a {" * depth + "x=1" + "}" * depth

  test("a thousand nested objects produce a report or a reason, never an exception") {
    DuplicateReport.findings(nested(1000)) match {
      case Right(found)  => assertEquals(found, Nil)
      case Left(refusal) => assert(refusal.reason.nonEmpty)
    }
  }

  test("a thousand nested objects beside an include produce a normal verdict") {
    Verdict.of("include \"missing.conf\"\n" + nested(1000)) match {
      case Verdict.Refused(refusal)      => assert(refusal.reason.nonEmpty)
      case Verdict.NeedsFormatting(text) => assert(text.contains("include \"missing.conf\""))
      case Verdict.AlreadyFormatted      => ()
    }
  }
}
