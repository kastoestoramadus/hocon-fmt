package eu.ww86.hoconfmt

import org.ekrich.config.ConfigValueType

/** MIXED: our acceptance policy and output pins for the renderer limitation reported as #654.
  * A renderer fix should change these expectations, rather than add a formatter refusal.
  */
class NumericRenderingSpec extends munit.FunSuite with HoconTestSupport {
  val spellings = List("1e5" -> "100000", "1.0" -> "1", "0.10" -> "0.1")

  spellings.foreach { case (sourceNumber, canonical) =>
    test(s"formatter: accepts numeric string conversion after rendering $sourceNumber") {
      val source = s"a = $sourceNumber\nb = $${a}ms"
      val output = formatted(source)
      assertEquals(output, s"a = $canonical\nb = $${a}ms\n")
      assertEquals(source.parsedConfig.resolve().getString("b"), s"${sourceNumber}ms")
      assertEquals(output.parsedConfig.resolve().getString("b"), s"${canonical}ms")
      assertEquals(HoconFormatter.format(output), Right(output))
    }
  }

  test("formatter: accepts numeric string conversion one substitution away") {
    val source = "a = 1e5\nb = ${a}\nc = ${b}ms"
    val output = formatted(source)
    assertEquals(output, "a = 100000\nb = ${a}\nc = ${b}ms\n")
    assertEquals(source.parsedConfig.resolve().getString("c"), "1e5ms")
    assertEquals(output.parsedConfig.resolve().getString("c"), "100000ms")
  }

  test("formatter: accepts an optional substitution of a nested number into a string") {
    val source = "o.a = 1e5\nb = ${?o.a}ms"
    val output = formatted(source)
    assertEquals(output, "o.a = 100000\nb = ${?o.a}ms\n")
    assertEquals(source.parsedConfig.resolve().getString("b"), "1e5ms")
    assertEquals(output.parsedConfig.resolve().getString("b"), "100000ms")
  }

  test("formatter: accepts numeric string conversion beside an include") {
    val source = "include \"defaults.conf\"\na = 1e5\nb = ${a}ms"
    assertEquals(formatted(source), "include \"defaults.conf\"\na = 100000\nb = ${a}ms\n")
  }

  test("formatter: accepts changed getString text without a substitution") {
    val source = "a = 1e5"
    val output = formatted(source)
    assertEquals(output, "a = 100000\n")
    assertEquals(source.parsedConfig.getString("a"), "1e5")
    assertEquals(output.parsedConfig.getString("a"), "100000")
  }

  test("formatter: accepts Infinity that re-reads as a string") {
    val source = "a = 1e400"
    val output = formatted(source)
    assertEquals(output, "a = Infinity\n")
    assertEquals(source.parsedConfig.getValue("a").valueType, ConfigValueType.NUMBER)
    assertEquals(output.parsedConfig.getValue("a").valueType, ConfigValueType.STRING)
    assertEquals(HoconFormatter.format(output), Right(output))
  }
}
