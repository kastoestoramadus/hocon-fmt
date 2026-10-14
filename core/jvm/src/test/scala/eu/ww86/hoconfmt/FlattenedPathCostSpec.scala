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
}
