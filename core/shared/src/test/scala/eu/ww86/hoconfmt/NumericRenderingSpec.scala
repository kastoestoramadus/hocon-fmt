package eu.ww86.hoconfmt

import org.ekrich.config.ConfigValueType

/** MIXED: output pins for numeric spelling under the document engine. The resolving render
  * canonicalised numbers — the renderer limitation reported as #654 — and this suite accepted and
  * pinned that (owner decision, 2026-10). The document engine (task 910) keeps the spelling as
  * written, so the pins hold the input's own lexeme and a formatted file resolves exactly as the
  * input did; a future engine that canonicalises again changes these expectations rather than
  * adding a formatter refusal.
  */
class NumericRenderingSpec extends munit.FunSuite with HoconTestSupport {
  val spellings = List("1e5", "1.0", "0.10", "007", "1e-400")

  spellings.foreach { sourceNumber =>
    test(s"formatter: keeps the numeric spelling as written: $sourceNumber") {
      val source = s"a = $sourceNumber\nb = $${a}ms"
      val output = formatted(source)
      assertEquals(output, s"a = $sourceNumber\nb = $${a}ms\n")
      assertEquals(source.parsedConfig.resolve().getString("b"), s"${sourceNumber}ms")
      assertEquals(output.parsedConfig.resolve().getString("b"), s"${sourceNumber}ms")
      assertEquals(HoconFormatter.format(output), Right(output))
    }
  }

  test("formatter: keeps the spelling one substitution away") {
    val source = "a = 1e5\nb = ${a}\nc = ${b}ms"
    val output = formatted(source)
    assertEquals(output, "a = 1e5\nb = ${a}\nc = ${b}ms\n")
    assertEquals(source.parsedConfig.resolve().getString("c"), "1e5ms")
    assertEquals(output.parsedConfig.resolve().getString("c"), "1e5ms")
  }

  test("formatter: keeps an optional substitution of a nested number into a string") {
    val source = "o.a = 1e5\nb = ${?o.a}ms"
    val output = formatted(source)
    assertEquals(output, "o.a = 1e5\nb = ${?o.a}ms\n")
    assertEquals(source.parsedConfig.resolve().getString("b"), "1e5ms")
    assertEquals(output.parsedConfig.resolve().getString("b"), "1e5ms")
  }

  test("formatter: keeps the spelling beside an include") {
    val source = "include \"defaults.conf\"\na = 1e5\nb = ${a}ms"
    assertEquals(formatted(source), "include \"defaults.conf\"\na = 1e5\nb = ${a}ms\n")
  }

  test("formatter: keeps the getString text without a substitution") {
    val source = "a = 1e5"
    val output = formatted(source)
    assertEquals(output, "a = 1e5\n")
    assertEquals(source.parsedConfig.getString("a"), "1e5")
    assertEquals(output.parsedConfig.getString("a"), "1e5")
  }

  if (!Variant.differs("numeric: Infinity")) {
    test("formatter: keeps an overflow double as the number it was written as") {
      val source = "a = 1e400"
      val output = formatted(source)
      assertEquals(output, "a = 1e400\n")
      assertEquals(source.parsedConfig.getValue("a").valueType, ConfigValueType.NUMBER)
      assertEquals(output.parsedConfig.getValue("a").valueType, ConfigValueType.NUMBER)
      assertEquals(HoconFormatter.format(output), Right(output))
    }
  }
}
