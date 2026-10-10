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

  /** One field per line, each under a key of two segments: a dotted key writes the object on the
    * way to its leaf, so the definitions are related to each other, the case in which a candidate
    * cannot be dismissed by its path alone.
    */
  val dottedFields: String = (1 to 4998).map(index => s"key$index.x=$index").mkString("\n") + "\n"

  test("a large file with two includes stays within ten times the include-free time") {
    val plain    = medianMillis(fields)
    val included = medianMillis(withIncludes)
    assert(
      included < plain * 10,
      s"the include-bearing file took ${included} ms against ${plain} ms without includes; " +
        "a scan quadratic in the definitions is back"
    )
  }

  /** The shapes a large file writes around an include when a concatenation is involved: an object
    * concatenation, an array concatenation whose later piece holds the definitions, and a dotted
    * one. Each with its two includes and beside the same shape without them — the includes stand at
    * the top, so every definition after them is a candidate, and a definition inside a later piece
    * of an array concatenation cannot be read off the merged tree at all. The bound compares each
    * shape with itself, so it tells the check's cost from the size of the file and the machine.
    */
  def concatenations: List[(String, (String, String))] = {
    val includes = "include \"f.conf\"\n"
    val after    = "include \"g.conf\"\n"
    List(
      "an object concatenation"      -> (s"app={} {\n$includes$fields$after}\n", s"app={} {\n$fields}\n"),
      "an array concatenation"       -> (s"rows=[0] [{\n$includes$fields$after}]\n", s"rows=[0] [{\n$fields}]\n"),
      "a dotted array concatenation" -> (
        s"rows=[0] [{\n$includes$dottedFields$after}]\n",
        s"rows=[0] [{\n$dottedFields}]\n"
      )
    )
  }

  test("a large concatenation keeps its includes within ten times the include-free time") {
    concatenations.foreach { case (name, (included, plain)) =>
      val free    = medianMillis(plain)
      val bearing = medianMillis(included)
      assert(
        bearing < free * 10,
        s"$name: the include-bearing file took $bearing ms against $free ms without includes; " +
          "a cost per definition is back where the merged tree cannot answer"
      )
    }
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
