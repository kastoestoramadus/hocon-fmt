package eu.ww86.hoconfmt

import scala.concurrent.duration.DurationInt

/** The duplicate report's work budget: a file whose key definitions' total squared flattened
  * length exceeds `HoconText.MaxPathWork` is refused by the scan, before anything is parsed. The
  * report pairs every prefix of every definition's path against the definitions
  * (`DuplicateReport.report`), so its cost follows the file's total work, not the longest single
  * statement the written and flattened limits cap: two 120-segment chains with 200 leaves of 120
  * segments each (160 KB, every written line at the limits, no duplicates) spent 12.0-12.8 s in
  * `DuplicateReport.findings` on the JVM while the scan accepted it. The budget refuses the shape
  * in under a millisecond — through the report the command line and the other integrations run,
  * and through `Verdict.of`, whose scan refuses before any parse. The tests here pin the
  * boundary from both sides, the array counting (each array element level is one more segment in
  * the report's walk), and the legitimate shapes that must survive it. `TimeoutGuard` makes a
  * regression fail the suite in seconds instead of hanging it for as long as the unbounded
  * report takes.
  */
class PathWorkBudgetSpec extends munit.FunSuite {

  val bound = 10.seconds

  test("many definitions of the same short path answer within the time bound") {
    val source = "x = 1\n" * 30000
    val result = TimeoutGuard.within(bound.toMillis)(DuplicateReport.findings(source))
    assertEquals(result.map(_.size), Right(29999))
    assertEquals(
      result.map(_.take(2)),
      Right(
        List(
          Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 2),
          Finding.KeyDefinedAgain(KeyPath.of("x"), 2, 3)
        )
      )
    )
    assertEquals(result.map(_.takeRight(1)), Right(List(Finding.KeyDefinedAgain(KeyPath.of("x"), 29999, 30000))))
  }

  def objectPath(segments: Int): String          = (1 to segments).map(i => s"k$i").mkString(".")
  def leafPath(leaf: Int, segments: Int): String = (1 to segments).map(j => s"l${leaf}k$j").mkString(".")

  /** Two nested 120-segment chains whose innermost object holds `leaves` distinct 120-segment
    * leaves, wrapped in `arrays` array levels: the shape family of the 30-shape search behind
    * the budget's calibration, from 50 leaves (3 s in the report on the JVM) to 1600 (122 s).
    */
  def dense(leaves: Int, arrays: Int = 0): String = {
    val open  = objectPath(120) + " {\n" + objectPath(120) + " = " + "[" * arrays + " {\n"
    val body  = (1 to leaves).map(i => leafPath(i, 120) + " = 1\n").mkString
    val close = "}" + "]" * arrays + "\n}\n"
    open + body + close
  }

  def expectBudgetRefusal(refusal: Refusal, shape: String): Unit = refusal match {
    case why @ Refusal.PathTooDeep(_) => assert(why.reason.contains("budget"), s"$shape: ${why.reason}")
    case other                        => fail(s"$shape: expected the work-budget refusal, got $other")
  }

  test("the over-10s shape, two 120-segment chains and 200 leaves of 120 segments, refuses within the time bound") {
    val source = dense(200) // the shape of the measured 160 KB file, total work ~2.2 billion
    TimeoutGuard.within(bound.toMillis)(DuplicateReport.findings(source)) match {
      case Left(refusal) => expectBudgetRefusal(refusal, "dense 200")
      case other         => fail(s"dense 200: expected the work-budget refusal, got $other")
    }
    Verdict.of(source) match {
      case Verdict.Refused(refusal) => expectBudgetRefusal(refusal, "dense 200")
      case other                    => fail(s"dense 200: expected a refused verdict, got $other")
    }
  }

  test("dense shapes of the 30-shape search above the budget refuse within the time bound") {
    for (shape <- List((400, 0), (200, 40), (1600, 0))) {
      val (leaves, arrays) = shape
      val source           = dense(leaves, arrays)
      TimeoutGuard.within(bound.toMillis)(DuplicateReport.findings(source)) match {
        case Left(refusal) => expectBudgetRefusal(refusal, s"dense $leaves x $arrays")
        case other         => fail(s"dense $leaves x $arrays: expected the work-budget refusal, got $other")
      }
    }
  }

  /** 7-segment leaves under a 7-segment chain: the chain counts 140 (S(7)), each leaf 875
    * (S(14) - S(7)), every other limit far away (written 7, flattened 14, depth 1).
    */
  def boundary(leaves: Int): String =
    objectPath(7) + " {\n" + (1 to leaves).map(i => leafPath(i, 7) + " = 1\n").mkString + "}\n"

  test("a file just under the work budget formats, one more leaf refuses") {
    val under = (HoconText.MaxPathWork - 140L) / 875L // 342 leaves: 299,390 of 300,000
    assert(HoconText.checkNesting(boundary(under.toInt)) == Right(()))
    assert(HoconFormatter.format(boundary(under.toInt)).isRight)
    Verdict.of(boundary((under + 1).toInt)) match {
      case Verdict.Refused(refusal) => expectBudgetRefusal(refusal, s"boundary ${under + 1}")
      case other                    => fail(s"boundary ${under + 1}: expected a refused verdict, got $other")
    }
  }

  test("every piece of an object concatenation inherits the field's path") {
    val path   = objectPath(7)
    val pieces = (1 to 343).map(i => "{ " + leafPath(i, 7) + " = 1 }").mkString(" ")
    HoconText.checkNesting(path + " = " + pieces) match {
      case Left(refusal) => expectBudgetRefusal(refusal, "object concatenation")
      case other         => fail(s"expected the work-budget refusal, got $other")
    }
  }

  test("every piece of an array concatenation inherits the field's path and one element level") {
    val path = objectPath(7)
    // S(7) + 292 * (9² + ... + 15²) = 302652, while omitting the field's
    // path on later pieces counts less than 60000.
    val pieces = (1 to 292).map(i => "[{ " + leafPath(i, 7) + " = 1 }]").mkString(" ")
    HoconText.checkNesting(path + " = " + pieces) match {
      case Left(refusal) => expectBudgetRefusal(refusal, "array concatenation")
      case other         => fail(s"expected the work-budget refusal, got $other")
    }
  }

  test("array levels have an exact budget boundary") {
    // S(8) + S(16)-S(8) + 31 * (37² + ... + 42²) = 292145.
    assertEquals(HoconText.checkNesting(arrayed(arrays = 20, leaves = 31)), Right(()))
    HoconText.checkNesting(arrayed(arrays = 20, leaves = 32)) match {
      case Left(refusal) => expectBudgetRefusal(refusal, "array boundary 32")
      case other         => fail(s"expected the work-budget refusal, got $other")
    }
  }

  test("array element levels count toward the budget: the same leaves refuse with arrays in the chain") {
    // 8-segment chain levels, 100 leaves of 6 segments: without array levels the leaves sit at
    // flattened 17..22 and the file counts 231,396, accepted. With 20 array levels the report
    // walks 37..42-segment paths and the leaves alone cost 100 x 9,379 — refused, although the
    // other limits stay far away (written 8, flattened 42, depth 22). A scan that skipped the
    // array levels, as the flattened count once did, counts 231,396 for both files.
    assert(HoconFormatter.format(arrayed(arrays = 0, leaves = 100)).isRight)
    Verdict.of(arrayed(arrays = 20, leaves = 100)) match {
      case Verdict.Refused(refusal) => expectBudgetRefusal(refusal, "arrayed 20 x 100")
      case other                    => fail(s"arrayed 20 x 100: expected a refused verdict, got $other")
    }
  }

  def arrayed(arrays: Int, leaves: Int): String =
    objectPath(8) + " {\n"
      + objectPath(8) + " = " + "[" * arrays + " {\n"
      + (1 to leaves).map(i => leafPath(i, 6) + " = 1\n").mkString
      + "}" + "]" * arrays + "\n"
      + "}\n"

  test("legitimate shapes stay accepted: the corpus-maximum shape, 79 short levels, corpus-worst-sized files") {
    // Depth 8 with 11-segment paths — the corpus-maximum shape NestingDepthSpec pins — counts
    // 238,965 (S(88) + 89^2), 80% of the budget. 79 levels of one-segment keys count 173,980
    // (S(79) + 80^2). Two thousand distinct 7-segment statements at the root — the shape of the
    // corpus's worst files, which count at most 26,725 — scale to 280,000, still under.
    assert(HoconFormatter.format((objectPath(11) + " {\n") * 8 + "leaf = 1\n" + "}\n" * 8).isRight)
    assert(HoconFormatter.format((1 to 79).map(i => s"a$i {\n").mkString + "leaf = 1\n" + "}\n" * 79).isRight)
    assert(HoconFormatter.format((1 to 2000).map(i => leafPath(i, 7) + " = 1\n").mkString).isRight)
  }
}
