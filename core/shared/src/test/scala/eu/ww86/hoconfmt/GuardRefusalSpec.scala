package eu.ww86.hoconfmt

/** The guards that refuse a lost or duplicated comment and a moved include exist for the day the
  * document engine regresses; no input the suite can reach makes it lose either (the engine keeps
  * comments and include statements verbatim, and the 13,272-case corpus sweep refused none), so
  * their refusals are pinned by injecting a losing render through [[HoconFormatter.formatInjecting]]
  * and asserting the file is refused rather than written. A guard whose verdict is discarded —
  * the refusal-discarding mutants of the 911 mutation report — turns every test here red.
  */
class GuardRefusalSpec extends munit.FunSuite {

  // The tweaks write the kind of text a renderer regression would: a comment gone, a comment
  // printed twice (the old `+=` desugaring did that), two includes written the wrong way round.
  // Each leaves text the second pass agrees with, so the refusal comes from the guard under test
  // and not from an unstable render.
  private def dropCommentLines(text: String): String =
    text.linesIterator
      .filterNot(line => {
        val trimmed = line.trim
        trimmed.startsWith("#") || trimmed.startsWith("//")
      })
      .mkString("", "\n", "\n")

  private def duplicateCommentLines(text: String): String =
    text.linesIterator
      .flatMap(line => if (line.trim.startsWith("#") || line.trim.startsWith("//")) List(line, line) else List(line))
      .mkString("", "\n", "\n")

  private def swapTwoLines(text: String): String =
    text.linesIterator.toList match {
      case List(first, second) => second + "\n" + first + "\n"
      case _                   => text
    }

  private def inject(source: String, tweak: String => String): Either[Refusal, String] =
    HoconFormatter.formatInjecting(source, None, FormatOptions.default, tweak)

  test("a render that drops the file's comment is refused") {
    assertEquals(inject("# c\na = 1\n", dropCommentLines), Left(Refusal.LostComment("c")))
  }

  test("a render that prints a comment twice is refused") {
    assertEquals(inject("# c\na = 1\n", duplicateCommentLines), Left(Refusal.LostComment("c")))
  }

  test("a render that swaps two includes is refused") {
    assertEquals(inject("include \"y\"\ninclude \"x\"\n", swapTwoLines), Left(Refusal.MovedInclude("include \"y\"")))
  }
}
