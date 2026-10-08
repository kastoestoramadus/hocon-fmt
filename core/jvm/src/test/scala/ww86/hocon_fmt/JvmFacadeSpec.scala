package ww86.hocon_fmt

import java.nio.charset.StandardCharsets.UTF_8

/** The facade the Java plugins call, and the sbt plugin calls across an isolated class loader. */
class JvmFacadeSpec extends munit.FunSuite {

  test("formatted content maps to empty") {
    assertEquals(JavaCaller.describe("a = 1\n".getBytes(UTF_8)), "already formatted")
  }

  test("unformatted content maps to the text it should have") {
    assertEquals(JavaCaller.describe("a   :   1".getBytes(UTF_8)), "reformat to: a = 1\n")
  }

  test("a refusal is a checked exception whose message is the reason") {
    val described = JavaCaller.describe("a : ${".getBytes(UTF_8))
    assert(described.startsWith("refused: "), described)
    assert(described.length > "refused: ".length, s"no reason given: $described")
  }

  test("a named file's refusal carries the file's name") {
    val described = JavaCaller.describeNamed("a : ${".getBytes(UTF_8), "conf/application.conf")
    assert(described.startsWith("refused: not valid HOCON: conf/application.conf:"), described)
  }

  // Lightbend's loader reads .json and .properties too; a round trip hands back HOCON, not the
  // file its name promises, so the name alone decides.
  test("a file whose name promises another format is refused, naming what it is") {
    val described = JavaCaller.describeNamed("{\"a\": 1}".getBytes(UTF_8), "application.json")
    assert(described.startsWith("refused: a JSON file"), described)
  }

  // The sbt 1.x plugin sees none of our classes, so it reaches the facade by name and reads the
  // result as JDK types. Renaming either side breaks it without a compile error anywhere.
  test("the facade is reachable reflectively, returning only JDK types") {
    val facade   = Class.forName("ww86.hocon_fmt.JvmFacade")
    val reformat = facade.getMethod("reformat", classOf[Array[Byte]])
    val result   = reformat.invoke(null, "a   :   1".getBytes(UTF_8))
    assertEquals(result, java.util.Optional.of("a = 1\n"))
    val named = facade.getMethod("reformat", classOf[Array[Byte]], classOf[String])
    assertEquals(named.invoke(null, "a   :   1".getBytes(UTF_8), "app.conf"), java.util.Optional.of("a = 1\n"))
  }
}
