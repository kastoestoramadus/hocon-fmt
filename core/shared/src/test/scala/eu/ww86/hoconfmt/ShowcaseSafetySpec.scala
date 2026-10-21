package eu.ww86.hoconfmt

class ShowcaseSafetySpec extends munit.FunSuite {
  // the resolving render dropped the earlier definition and the comment attached to it, which the
  // guard refused; the document engine keeps both definitions with the comment where it stood
  test("a dead dotted entry carrying a comment is kept where it stood") {
    val input   = "# Keep this operational note\nservice.port = 8080\nservice.port = 9000\n"
    val verdict = Verdict.of(input)
    println(s"Safety net today: $verdict")
    assertEquals(verdict, Verdict.AlreadyFormatted)
  }
}
