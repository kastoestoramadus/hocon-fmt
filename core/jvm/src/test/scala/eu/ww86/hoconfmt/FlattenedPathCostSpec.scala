package eu.ww86.hoconfmt

import scala.concurrent.duration.DurationInt

/** The duplicate report's time bound is real: `TimeoutGuard` runs the call on a worker thread
  * and the test thread gives up at the bound, so a regression fails the suite in seconds instead
  * of hanging it. The resource limits behind the refusals live in the scan — the written cap
  * and the work budget that bounds the report's total (`HoconText.MaxPathWork`,
  * pinned by `PathWorkBudgetSpec`): the shapes here are the ones that once spent tens of seconds
  * in the report, and every one of them must be refused within the bound. The shared property in
  * `FormatterPropertiesSpec` runs the same shapes with the bound asserted after the call, which
  * is all a single-threaded platform can do.
  */
class FlattenedPathCostSpec extends munit.FunSuite {

  val bound      = 10.seconds
  def workReason =
    s"too many or too long key paths to check safely; use shorter key paths or split the configuration into smaller files"

  def objectPath(segments: Int): String = (1 to segments).map(i => s"k$i").mkString(".")

  /** `levels` nested objects, each reached by a dotted key path of `segments` segments, with the
    * one-segment `leaf = 1` inside the deepest one.
    */
  def chain(levels: Int, segments: Int): String =
    (objectPath(segments) + " {\n") * levels + "leaf = 1\n" + "}\n" * levels

  def expectRefused[R](guarded: Either[Refusal, List[Finding]]): Unit =
    assertEquals(guarded.left.map(_.reason), Left(workReason))

  test("a 20-level chain of maximal written paths is refused within the time bound") {
    val source = chain(20, 120) // 2,401 flattened segments
    TimeoutGuard.within(bound.toMillis)(Verdict.of(source)) match {
      case Verdict.Refused(Refusal.PathTooDeep(_)) => ()
      case other                                   => fail(s"expected a refused verdict, got $other")
    }
    expectRefused(TimeoutGuard.within(bound.toMillis)(DuplicateReport.findings(source)))
  }

  test("the historical dense 360-segment shape refuses within the time bound") {
    // Two 120-segment chains and a 120-segment leaf: every written line at the written cap, 360
    // flattened segments, total work S(360) — the 50-leaf family of this shape spent 3.0-3.5 s
    // in the report on the JVM before the budget, 200 leaves 12.0-12.8 s.
    val source = (objectPath(120) + " {\n") * 2 + objectPath(120) + " = 1\n" + "}\n" * 2
    expectRefused(TimeoutGuard.within(bound.toMillis)(DuplicateReport.findings(source)))
  }

  test("six 120-segment levels, 720 flattened, are refused within the time bound") {
    val source = (objectPath(120) + " {\n") * 5 + objectPath(120) + " = 1\n" + "}\n" * 5 // 720 flattened
    TimeoutGuard.within(bound.toMillis)(Verdict.of(source)) match {
      case Verdict.Refused(Refusal.PathTooDeep(_)) => ()
      case other                                   => fail(s"expected a refused verdict, got $other")
    }
  }

  // The value spelling changes nothing: after `=`, `:` or `+=` (and the blanks, the newline or the
  // `[` of an array element after them) the scan must still push the path just written at the
  // `{`. Before the scan carried the count across a separator, the 20-level `= {` shape below
  // passed the scan and burned 25 seconds in the duplicate report on a file the parser refuses
  // for stack capacity, and the 361-segment shape formatted instead of refusing.
  def spelled(levels: Int, segments: Int, leafSegments: Int, spelling: String): String = {
    val opener = spelling match {
      case "eq"      => objectPath(segments) + " = {\n"
      case "colon"   => objectPath(segments) + " : {\n"
      case "append"  => objectPath(segments) + " += {\n"
      case "blanks"  => objectPath(segments) + "  =  {\n"
      case "newline" => objectPath(segments) + " =\n{\n"
      case "array"   => objectPath(segments) + " = [{\n"
    }
    val closer = if (spelling == "array") "}]\n" else "}\n"
    opener * levels
      + (if (leafSegments == 0) "leaf = 1\n" else objectPath(leafSegments) + " = 1\n")
      + closer * levels
  }

  for (spelling <- List("eq", "colon", "append", "blanks", "newline", "array")) {
    test(s"a 20-level chain written with `$spelling` is refused within the time bound") {
      val source = spelled(20, 120, 0, spelling) // 2,401 flattened segments
      TimeoutGuard.within(bound.toMillis)(Verdict.of(source)) match {
        case Verdict.Refused(Refusal.PathTooDeep(_)) => ()
        case other                                   => fail(s"expected a refused verdict, got $other")
      }
      expectRefused(TimeoutGuard.within(bound.toMillis)(DuplicateReport.findings(source)))
    }
  }

  for (spelling <- List("eq", "colon", "blanks", "newline", "array")) {
    test(s"a chain inside the budget written with `$spelling` formats within the time bound") {
      // The `+=` spelling is left out: nested `+=` is the parser's own limitation, not the
      // scan's, and its refusal says nothing about the bound.
      val source = spelled(2, 20, 20, spelling) // work S(60), every limit far away
      assert(TimeoutGuard.within(bound.toMillis)(HoconFormatter.format(source)).isRight)
    }
  }

  test("a scalar value lends its path to no later brace within the time bound") {
    val source = "x.y.z = 1\n" + ((objectPath(20) + " {\n") * 2 + objectPath(20) + " = 1\n" + "}\n" * 2)
    assert(TimeoutGuard.within(bound.toMillis)(HoconFormatter.format(source)).isRight)
  }
}
