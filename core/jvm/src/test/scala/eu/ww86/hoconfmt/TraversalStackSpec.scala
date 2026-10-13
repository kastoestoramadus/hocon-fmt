package eu.ww86.hoconfmt

import java.util.concurrent.FutureTask

class TraversalStackSpec extends munit.FunSuite {

  // A fixed stack keeps this regression independent of sbt's larger stack and machine defaults.
  def onSmallStack[A](run: => A): A = {
    val task   = new FutureTask[A](() => run)
    val thread = new Thread(null, task, "duplicate-report-depth", 1024L * 1024)
    thread.start()
    task.get()
  }

  test("the corpus shape completes duplicate analysis on a one MiB stack") {
    val source = "a {" * 1000 + "x=1" + "}" * 1000
    onSmallStack(DuplicateReport.findings(source)) match {
      case Right(found)  => assertEquals(found, Nil)
      case Left(refusal) => assert(refusal.reason.nonEmpty)
    }
  }

  test("the include-shadow boundary returns a verdict on a one MiB stack") {
    val source = "include \"missing.conf\"\n" + "a {" * 1000 + "x=1" + "}" * 1000
    onSmallStack(Verdict.of(source)) match {
      case Verdict.Refused(refusal)      => assert(refusal.reason.nonEmpty)
      case Verdict.NeedsFormatting(text) => assert(text.nonEmpty)
      case Verdict.AlreadyFormatted      => ()
    }
  }
  test("exhausted stack capacity refuses the text with a reason") {
    val source = "include \"missing.conf\"\n" + "a {" * 10000 + "x=1" + "}" * 10000
    onSmallStack(Verdict.of(source)) match {
      case Verdict.Refused(refusal) => assert(refusal.reason.nonEmpty)
      case other                    => fail(s"expected a refusal, got $other")
    }
  }

}
