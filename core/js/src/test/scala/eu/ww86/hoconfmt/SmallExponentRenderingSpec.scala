package eu.ww86.hoconfmt

/** MIXED: the sibling of `NumericRenderingSpec` for a spelling whose rendering is the platform's
  * `Double.toString`: Scala.js keeps `1e-7`, so nothing changes there; the JVM and Scala Native
  * give `1.0E-7` (`core/jvm-native` pins that).
  */
class SmallExponentRenderingSpec extends munit.FunSuite with HoconTestSupport {
  test("formatter: leaves 1e-7 as it is, so the string conversion does not change") {
    val source = "a = 1e-7\nb = ${a}ms"
    val output = formatted(source)
    assertEquals(output, "a = 1e-7\nb = ${a}ms\n")
    assertEquals(output.parsedConfig.resolve().getString("b"), "1e-7ms")
  }
}
