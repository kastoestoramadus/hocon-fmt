package eu.ww86.hoconfmt

/** The guard prefix is the literal both guard patterns must find, so `unmask` may skip both guard
  * passes on text without it. These inputs keep that skip honest: guard spellings that must be
  * dropped, near misses that must survive, the prefix buried where the scan is not token-aware —
  * inside strings and comments — and text with no guard at all.
  */
object GuardFieldDifferentialInputs {
  val field = "\"__INCLUDE_GUARD_0\" = \"g\""

  val adversarial: List[String] = {
    val guards = for {
      quote     <- List("", "\"")
      separator <- List(":", " = ", "\t:\r\n")
      key       <- List("0", "01", "7", "41", "999999999999999999999")
      value     <- List("g", "G", "gg")
    } yield s"$quote${IncludeMasking.GuardPrefix}$key$quote$separator$value"
    guards ++ List(
      // The prefix and its truncations hold no complete guard field, so nothing may be dropped.
      IncludeMasking.GuardPrefix,
      IncludeMasking.GuardPrefix + "0",
      IncludeMasking.GuardPrefix + "0 =",
      "__INCLUDE_GUARD",
      "__INCLUDE_GUAR",
      // Separators and values a guard field cannot have.
      "__INCLUDE_GUARD_0 == g",
      "__INCLUDE_GUARD_0 , g",
      "__INCLUDE_GUARD_0 = G",
      "__INCLUDE_GUARD_0 = gg",
      "__INCLUDE_GUARD_0 = \"G\"",
      // The scan is not token-aware: a guard spelling inside a string, a comment or a triple
      // quote is rewritten all the same, and the skip must not change that.
      "a = \"" + field + "\"",
      "# " + field + "\n",
      "// " + field + "\r\n",
      "\"\"\"" + field + "\"\"\"",
      // Placements the own-line pass reaches by indentation, the inline pass by a comma, and
      // both by the newline lent for a guard on the very first line.
      field + "\nx = 1",
      "\n  " + field,
      "\n\t" + field,
      "a = 1, " + field,
      field + "\r\n" + field,
      // Indices we never issued survive; one spelled with a leading zero parses to one we did.
      "__INCLUDE_GUARD_41 = g",
      "__INCLUDE_GUARD_999999999999999999999 = g",
      "__INCLUDE_GUARD_01 = g",
      // Arbitrary UTF-16 around a real guard.
      "\ud800" + field,
      "\udc00" + field,
      "\ud83d\ude00" + field,
      // Long texts: the skip must agree with the scan on both sides of a real guard, on a
      // placeholder without a guard, and on a long text with neither.
      "x" * 100000 + field,
      field + "x" * 100000,
      "x" * 100000,
      "__INCLUDE_0 : \"__INCLUDE_0\"\n" + "x" * 100000,
      "__INCLUDE_0 : \"__INCLUDE_0\"\nx = 1",
      // The exact field shapes the mask writes.
      IncludeMasking.mask("include \"one.conf\"").text,
      "a { b { " + IncludeMasking.mask("include \"one.conf\"").text + "c = 1 } }"
    )
  }

  // Raw text carries no guards in most fixtures. The masked form carries the guards the mask
  // wrote whenever the source mentions an include; the flanked form carries them on every input,
  // one on its own line and one inline behind a comma.
  def variants(source: String): List[String] = List(
    source,
    IncludeMasking.mask(source).text,
    field + "\n" + source + ", \"__INCLUDE_GUARD_1\" : \"g\""
  )
}

/** The guard drops are ours; the oracle isolates them from upstream parsing. */
class GuardFieldDifferentialSpec extends munit.FunSuite with PlaceholderFieldDifferentialChecks {
  test("guard dropping agrees with the old scan on guard-shaped and prefix-bearing text") {
    val inputs = GuardFieldDifferentialInputs.adversarial
    inputs.foreach(source => GuardFieldDifferentialInputs.variants(source).foreach(checkScan))
    println(s"guard differential: ${inputs.size} adversarial inputs, ${inputs.size * 3} scans")
  }

  test("guard dropping agrees with the old scan on the existing generated families") {
    val inputs = PlaceholderFieldDifferentialInputs.generated
    inputs.foreach(source => GuardFieldDifferentialInputs.variants(source).foreach(checkScan))
    println(s"guard differential: ${inputs.size} generated inputs, ${inputs.size * 3} scans")
  }
}
