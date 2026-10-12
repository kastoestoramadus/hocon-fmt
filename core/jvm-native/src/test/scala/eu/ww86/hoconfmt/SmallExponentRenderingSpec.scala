package eu.ww86.hoconfmt

/** MIXED: the sibling of `NumericRenderingSpec` for a spelling whose rendering is the platform's
  * `Double.toString`: the JVM and Scala Native give `1.0E-7`, Scala.js keeps `1e-7`
  * (`core/js` pins that).
  */
class SmallExponentRenderingSpec extends munit.FunSuite with HoconTestSupport {
  test("formatter: accepts 1e-7 rewritten to 1.0E-7 and the string conversion that follows") {
    val source = "a = 1e-7\nb = ${a}ms"
    val output = formatted(source)
    assertEquals(output, "a = 1.0E-7\nb = ${a}ms\n")
    assertEquals(source.parsedConfig.resolve().getString("b"), "1e-7ms")
    assertEquals(output.parsedConfig.resolve().getString("b"), "1.0E-7ms")
  }
}
