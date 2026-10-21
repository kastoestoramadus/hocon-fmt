package eu.ww86.hoconfmt

/** MIXED: the sibling of `NumericRenderingSpec` for a spelling whose resolving render was the
  * platform's `Double.toString` (JVM and Scala Native gave `1.0E-7`, Scala.js `1e-7`). The
  * document engine keeps the lexeme, so the formatted output no longer depends on the platform.
  */
class SmallExponentRenderingSpec extends munit.FunSuite with HoconTestSupport {
  test("formatter: keeps 1e-7 as written, platform independently") {
    val source = "a = 1e-7\nb = ${a}ms"
    val output = formatted(source)
    assertEquals(output, "a = 1e-7\nb = ${a}ms\n")
    assertEquals(source.parsedConfig.resolve().getString("b"), "1e-7ms")
    assertEquals(output.parsedConfig.resolve().getString("b"), "1e-7ms")
  }
}
