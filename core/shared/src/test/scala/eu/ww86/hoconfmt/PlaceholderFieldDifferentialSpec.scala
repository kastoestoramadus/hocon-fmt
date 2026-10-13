package eu.ww86.hoconfmt

import org.scalacheck.Gen
import org.scalacheck.rng.Seed

object PlaceholderFieldDifferentialInputs {
  val originals: Map[Int, String] = (0 to 40).map(i => i -> s"include \"file-$i.conf\"").toMap
  val field                       = "\"__INCLUDE_0\" = \"__INCLUDE_0\""

  val adversarial: List[String] = {
    val substitutions = List(
      "${",
      "${?",
      "$${",
      "\\${",
      "$",
      "\"${x}\"",
      "\"\"\"${x}\"\"\"",
      "# ${x}\n",
      "// ${x}\n",
      "a { b { c = ${?x} } }",
      "x" * 100000 + "$",
      "a = ${x}\r\nb = ${?x}\r\n",
      "\ud800${x}",
      "\udc00${x}",
      "\ud83d\ude00${x}"
    )
    val placeholders = for {
      quote     <- List("", "\"")
      separator <- List(":", " = ", "\t:\r\n")
      key       <- List("0", "01", "999999999999999999999")
      value     <- List(key, "1")
    } yield s"$quote${IncludeMasking.PlaceholderPrefix}$key$quote$separator$quote${IncludeMasking.PlaceholderPrefix}$value$quote"
    substitutions ++ placeholders ++ List(
      field,
      "\"" + field,
      "__INCLUDE_0 : __INCLUDE_1 : __INCLUDE_1",
      "__INCLUDE_0 : \"__INCLUDE_1\" : \"__INCLUDE_1\"",
      "__INCLUDE_0 : __INCLUDE_0 : __INCLUDE_0",
      "__INCLUDE_",
      "__INCLUDE_GUARD_0 = g",
      "__INCLUDE_0 ! __INCLUDE_0",
      "\ud800" + field,
      "\udc00" + field,
      "\ud83d\ude00" + field,
      "x" * 100000 + field,
      field + "x" * 100000,
      "\"" + field + "\"",
      "\"\"\"" + field + "\"\"\"",
      "# " + field + "\n",
      "// " + field + "\r\n",
      "a { b { " + field + " } }",
      field + "\r\n" + field,
      "__INCLUDE_0 : " + " " * 100000 + "__INCLUDE_0"
    )
  }

  val generated: List[String] = {
    val family = Gen.oneOf(
      HoconGen.documents(includes = true),
      HoconGen.documents(includes = false),
      HoconGen.documentsWithConcatenations
    )
    (0 until 1000).toList.map { i =>
      family(Gen.Parameters.default, Seed(i.toLong)).fold(sys.error(s"generator exhausted at seed $i"))(_.text)
    }
  }

  // Raw text includes no placeholders in most fixtures. Masked and flanked forms force the
  // scan to encounter real fields, including after arbitrary UTF-16 and substitution syntax.
  def variants(source: String): List[String] = List(
    source,
    IncludeMasking.mask(source).text,
    field + "\n" + source + "\n\"__INCLUDE_1\" : \"__INCLUDE_1\""
  )
}

trait PlaceholderFieldDifferentialChecks extends munit.Assertions {
  def checkScan(source: String): Unit = {
    val originals = PlaceholderFieldDifferentialInputs.originals
    assertEquals(IncludeMasking.unmask(source, originals), PlaceholderFieldOracle.unmask(source, originals), source)
    // Ordered strings, rather than a count or set, pin both restoration and lost-include order.
    assertEquals(IncludeMasking.lost(source, originals), PlaceholderFieldOracle.lost(source, originals), source)
    val masked = IncludeMasking.Masked(source, originals)
    assertEquals(IncludeMasking.collides(masked, source), PlaceholderFieldOracle.collides(masked, source), source)
    val issued = IncludeMasking.mask("include \"one.conf\"\ninclude \"two.conf\"")
    assertEquals(IncludeMasking.collides(issued, source), PlaceholderFieldOracle.collides(issued, source), source)
  }

  def checkInputs(inputs: List[String]): Unit = inputs.foreach { source =>
    PlaceholderFieldDifferentialInputs.variants(source).foreach(checkScan)
  }
}

class PlaceholderFieldDifferentialSpec extends munit.FunSuite with PlaceholderFieldDifferentialChecks {
  test("placeholder finding agrees with the old scan on adversarial UTF-16 and overlapping fields") {
    val inputs = PlaceholderFieldDifferentialInputs.adversarial
    checkInputs(inputs)
    println(s"placeholder differential: ${inputs.size} adversarial inputs, ${inputs.size * 3} scans")
  }

  test("placeholder finding agrees with the old scan on the existing generated families") {
    val inputs = PlaceholderFieldDifferentialInputs.generated
    checkInputs(inputs)
    println(s"placeholder differential: ${inputs.size} generated inputs, ${inputs.size * 3} scans")
  }
}
