package eu.ww86.hoconfmt

import scala.concurrent.duration.*

class TraversalDepthSpec extends munit.FunSuite {

  // Native debug retains the report's deep-path comparison cost; this checks stack safety.
  override def munitTimeout: Duration = 2.minutes

  def nested(depth: Int): String = "a {" * depth + "x=1" + "}" * depth

  // 80 is the guard's limit: deeper input never reaches the traversal these specs are about.
  val depth = 80

  test("the deepest accepted nesting produces a report or a reason, never an exception") {
    DuplicateReport.findings(nested(depth)) match {
      case Right(found)  => assertEquals(found, Nil)
      case Left(refusal) => assert(!refusal.reason.contains("maximum depth"), refusal.reason)
    }
  }

  test("the deepest accepted nesting beside an include produces a normal verdict") {
    Verdict.of("include \"missing.conf\"\n" + nested(depth)) match {
      case Verdict.Refused(refusal)      => assert(!refusal.reason.contains("maximum depth"), refusal.reason)
      case Verdict.NeedsFormatting(text) => assert(text.contains("include \"missing.conf\""))
      case Verdict.AlreadyFormatted      => ()
    }
  }
}
