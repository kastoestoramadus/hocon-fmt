package eu.ww86.hoconfmt

class ShowcaseSafetySpec extends munit.FunSuite {
  test("a dead dotted entry carrying a comment is refused permanently") {
    val input   = "# Keep this operational note\nservice.port = 8080\nservice.port = 9000\n"
    val verdict = Verdict.of(input)
    println(s"Safety net today: $verdict")
    assertEquals(verdict, Verdict.Refused(Refusal.LostComment("Keep this operational note")))
  }
}
