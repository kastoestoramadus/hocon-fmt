package eu.ww86.hoconfmt

/** The merge budget: sconfig resolves N definitions that merge through one key in time that
  * grows with N^2, so the scan counts, per object scope, the definitions each first written key
  * segment accumulates — dotted spellings of the segment's children and `+=` repeats alike — and
  * refuses past the budget, before the parser spends it. The nested spelling, distinct first
  * keys and plain one-segment repeats define nothing through one key and are not charged.
  */
class MergeBudgetSpec extends munit.FunSuite {

  // isqrt(HoconText.MaxMergeWork): 1414^2 = 1,999,396 charges through one key are the last
  // accepted count, 1415^2 = 2,002,225 the first refused one.
  val boundary = 1414

  def mergeReason =
    "too many definitions merge through the same key; write them nested in { } or as one [ ] array, or split the file"

  def dotted(n: Int): String      = (0 until n).map(i => s"a.b.k$i = 1\n").mkString
  def plus(n: Int): String        = List.fill(n)("k += 1\n").mkString
  def nested(n: Int): String      = "a {\n" + (0 until n).map(i => s"k$i = 1\n").mkString + "}\n"
  def independent(n: Int): String = (0 until n).map(i => s"x$i.k = 1\n").mkString
  def plain(n: Int): String       = List.fill(n)("k = 1\n").mkString

  test("the last accepted number of dotted definitions through one key passes the scan; one more refuses") {
    assertEquals(HoconText.checkNesting(dotted(boundary)), Right(()))
    assertEquals(HoconText.checkNesting(dotted(boundary + 1)).left.map(_.reason), Left(mergeReason))
  }

  test("repeated += on one key is the same defect and the same budget") {
    assertEquals(HoconText.checkNesting(plus(boundary)), Right(()))
    assertEquals(HoconText.checkNesting(plus(boundary + 1)).left.map(_.reason), Left(mergeReason))
  }

  test("the nested spelling, distinct first keys and plain repeats are not charged") {
    assertEquals(HoconText.checkNesting(nested(boundary + 1)), Right(()))
    assertEquals(HoconText.checkNesting(independent(boundary + 1)), Right(()))
    assertEquals(HoconText.checkNesting(plain(boundary + 1)), Right(()))
  }

  test("dotted definitions and += repeats through one key share one budget") {
    val plusOnA = List.fill(708)("a += 1\n").mkString
    assertEquals(HoconText.checkNesting(dotted(708) + plusOnA).left.map(_.reason), Left(mergeReason))
    assertEquals(HoconText.checkNesting(dotted(708) + plus(708)), Right(()))
  }

  test("two object scopes charge separately") {
    val split = "{\n" + dotted(708) + "}\n{\n" + dotted(708) + "}\n"
    assertEquals(HoconText.checkNesting(split), Right(()))
    assertEquals(HoconText.checkNesting(dotted(1416)).left.map(_.reason), Left(mergeReason))
  }

  test("a quoted first segment and its unquoted spelling are one key") {
    def alternating(even: String, odd: String, n: Int): String =
      (0 until n).map(i => s"${if (i % 2 == 0) even else odd}.b.k$i = 1\n").mkString
    // `"a".b.ki` and `a.b.ki` merge through one key: 1416 charges, over the budget.
    assertEquals(HoconText.checkNesting(alternating("\"a\"", "a", 1416)).left.map(_.reason), Left(mergeReason))
    // A different first key every other line halves both counts: under the budget.
    assertEquals(HoconText.checkNesting(alternating("\"a\"", "c", 1416)), Right(()))
  }

  test("the refusal names the remedy and fits the CLI's 120-character cut of reasons") {
    val reason = HoconText.checkNesting(dotted(boundary + 1)).swap.toOption.get.reason
    assertEquals(reason, mergeReason)
    assert(reason.length <= 115, s"${reason.length} characters: $reason")
    assert(reason.contains("nested"))
    assert(reason.contains("[ ]"))
  }

  test("format, verdict and duplicate analysis refuse the over-budget shape before parsing") {
    assertEquals(HoconFormatter.format(dotted(boundary + 1)).left.map(_.reason), Left(mergeReason))
    Verdict.of(plus(boundary + 1)) match {
      case Verdict.Refused(refusal) => assertEquals(refusal.reason, mergeReason)
      case other                    => fail(s"expected a refused verdict, got $other")
    }
    assertEquals(DuplicateReport.findings(dotted(boundary + 1)).left.map(_.reason), Left(mergeReason))
  }

  test("the last accepted dotted shape formats") {
    val dottedOut = HoconFormatter.format(dotted(boundary))
    assert(dottedOut.isRight, dottedOut.toString)
  }

  test("repeated += below the budget formats and keeps every append") {
    // The document engine keeps each `k += 1` as written; only a chain above the budget is
    // refused, at the scan, before the parser spends its quadratic time on it.
    assertEquals(HoconFormatter.format(plus(20)), Right(plus(20)))
  }

  test("a corpus-shaped file of 18 dotted keys under one prefix formats") {
    assert(HoconFormatter.format(dotted(18)).isRight)
  }
}
