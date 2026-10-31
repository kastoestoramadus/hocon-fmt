package eu.ww86.hoconfmt

/** The guards that refuse a lost or duplicated comment and a moved include exist for the day the
  * document engine regresses; no input the suite can reach makes it lose either (the engine keeps
  * comments and include statements verbatim, and the 13,272-case corpus sweep refused none), so
  * their refusals are pinned by injecting a losing render through [[HoconFormatter.formatInjecting]]
  * and asserting the file is refused rather than written. A guard whose verdict is discarded —
  * the refusal-discarding mutants of the 911 mutation report — turns every test here red.
  *
  * An include guard that checked membership (`contains`) or zipped the two statements lists
  * without counting them would read a render that loses one of several identical includes — or
  * prints one more than the source gave — as agreement, because the occurrences that survive hide
  * the one that changed. The tests here pin that multiplicity: one occurrence of a repeated
  * include gone, an include one time too many, two different includes with the last gone. The
  * first pass's include-present check reads the real render before any injection can reach it, so
  * its seam, [[HoconFormatter.includeStatementsPresent]], is pinned directly.
  */
class GuardRefusalSpec extends munit.FunSuite {

  // The tweaks write the kind of text a renderer regression would: a comment gone, a comment
  // printed twice (the old `+=` desugaring did that), two includes written the wrong way round,
  // an include line dropped or printed again. Each leaves text the second pass agrees with, so
  // the refusal comes from the guard under test and not from an unstable render.
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

  private def dropLastIncludeLine(text: String): String = {
    val lines = text.linesIterator.toList
    lines.patch(lines.lastIndexWhere(_.trim.startsWith("include")), Nil, 1).mkString("", "\n", "\n")
  }

  private def appendIncludeLine(text: String): String =
    text.linesIterator.mkString("", "\n", "\n") + "include \"a.conf\"\n"

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

  test("a render that loses one of two identical includes is refused") {
    assertEquals(
      inject("include \"a.conf\"\ninclude \"a.conf\"\n", dropLastIncludeLine),
      Left(Refusal.LostInclude("include \"a.conf\""))
    )
  }

  test("a render that prints an include one time too many is refused") {
    assertEquals(inject("include \"a.conf\"\n", appendIncludeLine), Left(Refusal.LostInclude("include \"a.conf\"")))
  }

  test("a render that drops the last of two different includes is refused") {
    assertEquals(
      inject("include \"a.conf\"\ninclude \"b.conf\"\n", dropLastIncludeLine),
      Left(Refusal.LostInclude("include \"b.conf\""))
    )
  }

  // The multiplicity check must not turn a valid file into a refusal: repeated identical includes
  // are ordinary HOCON, and the spacing the engine normalizes must still count as the statement
  // the source wrote.
  test("repeated identical includes still format, both kept") {
    assertEquals(
      HoconFormatter
        .format("include \"a.conf\"\ninclude \"a.conf\"\n")
        .map(_.linesIterator.count(_.trim.startsWith("include"))),
      Right(2)
    )
  }

  test("spacing the engine normalizes does not read as a changed include") {
    assertEquals(
      HoconFormatter
        .format("include   \"a.conf\"\ninclude \"a.conf\"\n")
        .map(_.linesIterator.count(_.trim.startsWith("include"))),
      Right(2)
    )
  }

  // The include-present check reads the real render before the tweak runs, so the injections above
  // cannot reach it and its seam is called directly: the render's statements as the locator reads
  // them, the source's as the pipeline carried them.
  test("the present check misses one of two identical includes no more") {
    assertEquals(
      HoconFormatter.includeStatementsPresent(
        "include \"a.conf\"\n",
        List("include \"a.conf\"", "include \"a.conf\"")
      ),
      Left(Refusal.LostInclude("include \"a.conf\""))
    )
  }

  test("the present check keeps counting the occurrences that survive") {
    assertEquals(
      HoconFormatter.includeStatementsPresent(
        "include \"a.conf\"\ninclude \"a.conf\"\n",
        List("include \"a.conf\"", "include \"a.conf\"")
      ),
      Right(())
    )
  }

  test("the present check is loss-only: an occurrence the source did not give is not its refusal") {
    assertEquals(
      HoconFormatter.includeStatementsPresent(
        "include \"a.conf\"\ninclude \"a.conf\"\n",
        List("include \"a.conf\"")
      ),
      Right(())
    )
  }

  test("the present check counts collapsed spellings together and names the first lost occurrence") {
    assertEquals(
      HoconFormatter.includeStatementsPresent(
        "include \"a.conf\"\n",
        List("include  \"a.conf\"", "include \"a.conf\"")
      ),
      Left(Refusal.LostInclude("include  \"a.conf\""))
    )
  }
}
