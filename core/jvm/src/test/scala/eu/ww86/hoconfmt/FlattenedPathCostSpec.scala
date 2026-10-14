package eu.ww86.hoconfmt

import scala.concurrent.duration.DurationInt

/** The flattened-path refusal exists so a hand-written file cannot spend unbounded time in
  * `format`, `Verdict.of` or the duplicate report through nesting — before the scan counted the
  * flattened path, a 20-level chain of 120-segment paths (2,401 flattened segments, 16.8 KB)
  * took 34 seconds in the duplicate report through the command line, on a file the formatter
  * refuses anyway. The bound here is real: `TimeoutGuard` runs the call on a worker thread and
  * the test thread gives up at the bound, so a regression fails the suite in seconds instead of
  * hanging it. The shared property in `FormatterPropertiesSpec` runs the same shapes with the
  * bound asserted after the call, which is all a single-threaded platform can do.
  */
class FlattenedPathCostSpec extends munit.FunSuite {

  val bound = 10.seconds

  def objectPath(segments: Int): String = (1 to segments).map(i => s"k$i").mkString(".")

  /** `levels` nested objects, each reached by a dotted key path of `segments` segments, with the
    * one-segment `leaf = 1` inside the deepest one.
    */
  def chain(levels: Int, segments: Int): String =
    (objectPath(segments) + " {\n") * levels + "leaf = 1\n" + "}\n" * levels

  test("a 20-level chain of maximal written paths is refused within the time bound") {
    val source = chain(20, 120) // 2,401 flattened segments
    assertEquals(TimeoutGuard.within(bound.toMillis)(Verdict.of(source)), Verdict.Refused(Refusal.PathTooDeep(720)))
    assertEquals(TimeoutGuard.within(bound.toMillis)(DuplicateReport.findings(source)), Left(Refusal.PathTooDeep(720)))
  }

  test("a chain at the flattened limit formats within the time bound") {
    val source = (objectPath(120) + " {\n") * 5 + objectPath(120) + " = 1\n" + "}\n" * 5 // 720 flattened
    assert(TimeoutGuard.within(bound.toMillis)(HoconFormatter.format(source)).isRight)
  }

  // The value spelling changes nothing: after `=`, `:` or `+=` (and the blanks, the newline or the
  // `[` of an array element after them) the scan must still push the path just written at the
  // `{`. Before the scan carried the count across a separator, the 20-level `= {` shape below
  // passed the scan and burned 25 seconds in the duplicate report on a file the parser refuses
  // for stack capacity, and the 721-segment shape formatted instead of refusing.
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
      assertEquals(TimeoutGuard.within(bound.toMillis)(Verdict.of(source)), Verdict.Refused(Refusal.PathTooDeep(720)))
      assertEquals(
        TimeoutGuard.within(bound.toMillis)(DuplicateReport.findings(source)),
        Left(Refusal.PathTooDeep(720))
      )
    }
  }

  for (spelling <- List("eq", "colon", "blanks", "newline", "array")) {
    test(s"a chain at the flattened limit written with `$spelling` formats within the time bound") {
      // The `+=` spelling is left out: nested `+=` is the parser's own limitation, not the
      // scan's, and its refusal says nothing about the bound.
      val source = spelled(5, 120, 120, spelling) // 720 flattened segments
      assert(TimeoutGuard.within(bound.toMillis)(HoconFormatter.format(source)).isRight)
    }
  }

  test("a scalar value lends its path to no later brace within the time bound") {
    val source = "x.y.z = 1\n" + ((objectPath(120) + " {\n") * 5 + objectPath(120) + " = 1\n" + "}\n" * 5)
    assert(TimeoutGuard.within(bound.toMillis)(HoconFormatter.format(source)).isRight)
  }
}
