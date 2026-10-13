package eu.ww86.hoconfmt

import scala.concurrent.duration.*

class TraversalDepthSpec extends munit.FunSuite {

  // Native debug retains the report's deep-path comparison cost; this checks stack safety.
  override def munitTimeout: Duration = 2.minutes

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
