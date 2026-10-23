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

  // The guard refuses past 80 levels, so the public entry points reach the traversal only up to
  // there; the outline is the entry below the guard and takes the depth the guard would stop.
  test("the deepest accepted nesting completes duplicate analysis on a one MiB stack") {
    val source = "a {" * 80 + "x=1" + "}" * 80
    onSmallStack(DuplicateReport.findings(source)) match {
      case Right(found)  => assertEquals(found, Nil)
      case Left(refusal) => fail(refusal.reason)
    }
  }

  // UPSTREAM-SCONFIG: the fork parser caps nesting at 100 levels, including the root.
  // Exercise the deepest tree it exposes; the public report guard still stops at 80.
  test("the outline walk at the fork parser boundary completes on a one MiB stack") {
    val depth  = 99
    val source = "a {" * depth + "x=1" + "}" * depth
    onSmallStack(DuplicateReport.outline(source)) match {
      case Right(outline) =>
        assertEquals(outline.definitions.map(_.keyPath.segments.size), (1 to depth + 1).toVector)
      case Left(refusal)  => fail(refusal.reason)
    }
  }

  test("the include-shadow boundary returns a verdict at the deepest accepted nesting on a one MiB stack") {
    val source = "include \"missing.conf\"\n" + "a {" * 80 + "x=1" + "}" * 80
    onSmallStack(Verdict.of(source)) match {
      case Verdict.Refused(refusal)      => assert(!refusal.reason.contains("maximum depth"), refusal.reason)
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
