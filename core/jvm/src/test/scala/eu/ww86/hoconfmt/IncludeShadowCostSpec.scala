package eu.ww86.hoconfmt

/** The include-shadow check reads every definition of a file that has an include, so its cost has
  * to stay a small multiple of the formatting around it. This is a regression guard, not a
  * benchmark: the bound is deliberately generous, and the ratio it checks is robust to the machine,
  * where milliseconds would not be. It was added after the same-line fallback turned a five-thousand
  * line file from tens of milliseconds into hundreds by scanning every definition for every
  * surviving one.
  */
class IncludeShadowCostSpec extends munit.FunSuite {

  val fields: String       = (1 to 4998).map(index => s"key$index=$index").mkString("\n") + "\n"
  val withIncludes: String = s"include \"f.conf\"\n$fields" + "include \"g.conf\"\n"

  test("a large file with two includes stays within ten times the include-free time") {
    val plain    = medianMillis(fields)
    val included = medianMillis(withIncludes)
    assert(
      included < plain * 10,
      s"the include-bearing file took ${included} ms against ${plain} ms without includes; " +
        "a scan quadratic in the definitions is back"
    )
  }

  /** The median of five whole formats after a warmup, in milliseconds. */
  def medianMillis(text: String): Double = {
    (1 to 3).foreach(_ => format(text))
    val times = (1 to 5).map { _ =>
      val start = System.nanoTime()
      val _     = format(text)
      (System.nanoTime() - start) / 1000000.0
    }
    times.sorted.apply(2)
  }

  def format(text: String): String =
    HoconFormatter.format(text).fold(refusal => fail(s"refused: ${refusal.reason}"), identity)
}
