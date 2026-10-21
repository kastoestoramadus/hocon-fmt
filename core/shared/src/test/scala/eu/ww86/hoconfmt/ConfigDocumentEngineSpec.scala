package eu.ww86.hoconfmt

/** The document-engine contract of `format`: sconfig's `ConfigDocument.format` (PR #655) formats
  * the text as written — duplicate definitions, `+=`, substitutions, numeric and quoted spelling,
  * include statements and comment spelling survive — while the wrapper keeps its own promises:
  * the preamble bytes, the refusals, the fixed point and the option mapping.
  *
  * Written before the engine switch (task 910): every expectation here is the document engine's
  * behaviour, so the suite is red against the resolving-render pipeline and green after it. Where
  * the first run corrected a derived expectation, `changed-expectations.md` in the task results
  * records the input, the old and the new output and why.
  */
// The helpers live in a trait: a method the class itself defines cannot be verified as
// transitively initialized when a test closure registered in the constructor calls it.
trait ConfigDocumentEngineSupport extends munit.FunSuite {
  def fmt(source: String, options: FormatOptions = FormatOptions.default): Either[Refusal, String] =
    HoconFormatter.format(source, options)

  def assertFormats(source: String, expected: String, options: FormatOptions = FormatOptions.default)(
      using munit.Location
  ): Unit =
    assertEquals(fmt(source, options), Right(expected), s"input: ${source.replace("\n", "\\n")}")
}

class ConfigDocumentEngineSpec extends munit.FunSuite with ConfigDocumentEngineSupport {

  // ---- includes: formatted as document nodes, never loaded (fixture D)

  test("an include between duplicate assignments is kept with both definitions, without loading it") {
    assertFormats(
      "a = 1\ninclude \"not-there-dev.conf\"\na = 2\n",
      "a = 1\ninclude \"not-there-dev.conf\"\na = 2\n"
    )
  }

  test("a url include is kept verbatim and never fetched") {
    assertFormats(
      "include url(\"http://127.0.0.1:9/none.conf\")\nb = 1\n",
      "include url(\"http://127.0.0.1:9/none.conf\")\nb = 1\n"
    )
  }

  test("every include form is kept in place") {
    assertFormats(
      "include \"a.conf\"\nb {\ninclude file(\"b.conf\")\ninclude classpath(\"c.conf\")\n" +
        "include required(file(\"e.conf\"))\nx = 1\n}\n",
      "include \"a.conf\"\nb {\n  include file(\"b.conf\")\n  include classpath(\"c.conf\")\n" +
        "  include required(file(\"e.conf\"))\n  x = 1\n}\n"
    )
  }

  // ---- kept as written (fixtures B and the duplicate headline)

  test("plus-equals is kept verbatim with its comment once") {
    assertFormats("a = [1]\na += 2 # add\n", "a = [1]\na += 2 # add\n")
  }

  test("duplicate keys and objects are both kept") {
    assertFormats("a = 1\na = 2\nb { x = 1 }\nb { y = 2 }\n", "a = 1\na = 2\nb { x = 1 }\nb { y = 2 }\n")
  }

  test("numeric and quoted spelling is kept") {
    assertFormats(
      "a = 1e400\nb = 1.50\nc = 0x10\nd = \"  spaced  \"\n",
      "a = 1e400\nb = 1.50\nc = 0x10\nd = \"  spaced  \"\n"
    )
  }

  test("substitutions are kept unresolved") {
    assertFormats("a = ${b}\nc = ${?d}\n", "a = ${b}\nc = ${?d}\n")
  }

  // ---- comments (fixture A)

  test("slash-slash comment spelling is kept") {
    assertFormats("// lead\na = 1\n", "// lead\na = 1\n")
  }

  test("a comment between a key and its value is kept as written") {
    assertFormats("a = # note\n  1\n", "a = # note\n  1\n")
  }

  test("an array tail comment is kept") {
    assertFormats("a = [\n  1, # one\n  2\n]\n", "a = [\n  1 # one\n  2\n]\n")
  }

  test("a comment before a closing brace is kept") {
    assertFormats("a {\n  b = 1\n  # tail\n}\n", "a {\n  b = 1\n  # tail\n}\n")
  }

  test("a footer comment is kept") {
    assertFormats("a = 1\n\n# trailing\n", "a = 1\n\n# trailing\n")
  }

  test("a comment-only file stays byte-exact") {
    assertFormats("  # just a comment\n", "  # just a comment\n")
  }

  // ---- the wrapper's own promises

  test("a detached header block stays byte-exact") {
    assertFormats(
      "# Copyright\n  # line 2\n\n\napp {\nname = x\n}\n",
      "# Copyright\n  # line 2\n\n\napp.name = x\n"
    )
  }

  test("a root array is formatted, not refused") {
    assertFormats("[1,\n2]", "[\n  1\n  2\n]\n")
  }

  test("formatting the output again changes nothing") {
    fmt("a=1\ninclude \"f.conf\"\na+=2\n") match {
      case Right(once) => assertEquals(fmt(once), Right(once))
      case Left(refusal) => fail(s"format refused: ${refusal.reason}")
    }
  }

  test("formatting the same source twice agrees") {
    val source = "a = 1\ninclude \"f.conf\"\na = 2\n"
    assertEquals(fmt(source), fmt(source))
  }

  test("broken input still refuses as NotHocon") {
    assert(fmt("{").swap.exists(_.reason.startsWith("not valid HOCON")))
  }

  test("an unpaired surrogate is still refused, not written") {
    assert(fmt("a = \"" + "\uD800" + "\"\n").swap.exists(_.reason.startsWith("the output would not parse again")))
  }

  // ---- the option mapping, all eight combinations
  //
  // separator -> colonAssign, doubleIndent -> doubleIndent, simplifyNestedObjects (our default
  // true, passed explicitly against an upstream default of false). The input is already laid out
  // so each expectation isolates the option's own effect.

  def nested = "a {\nb {\nc = 1\nd = 2\n}\n}\n"

  def expectations: List[(FormatOptions, String)] = List(
    (
      FormatOptions(separator = Separator.Equals, doubleIndent = false, simplifyNestedObjects = true),
      "a.b {\n  c = 1\n  d = 2\n}\n"
    ),
    (
      FormatOptions(separator = Separator.Colon, doubleIndent = false, simplifyNestedObjects = true),
      "a.b {\n  c: 1\n  d: 2\n}\n"
    ),
    (
      FormatOptions(separator = Separator.Equals, doubleIndent = true, simplifyNestedObjects = true),
      "a.b {\n    c = 1\n    d = 2\n}\n"
    ),
    (
      FormatOptions(separator = Separator.Colon, doubleIndent = true, simplifyNestedObjects = true),
      "a.b {\n    c: 1\n    d: 2\n}\n"
    ),
    (
      FormatOptions(separator = Separator.Equals, doubleIndent = false, simplifyNestedObjects = false),
      "a {\n  b {\n    c = 1\n    d = 2\n  }\n}\n"
    ),
    (
      FormatOptions(separator = Separator.Colon, doubleIndent = false, simplifyNestedObjects = false),
      "a {\n  b {\n    c: 1\n    d: 2\n  }\n}\n"
    ),
    (
      FormatOptions(separator = Separator.Equals, doubleIndent = true, simplifyNestedObjects = false),
      "a {\n    b {\n        c = 1\n        d = 2\n    }\n}\n"
    ),
    (
      FormatOptions(separator = Separator.Colon, doubleIndent = true, simplifyNestedObjects = false),
      "a {\n    b {\n        c: 1\n        d: 2\n    }\n}\n"
    )
  )

  expectations.foreach { case (options, expected) =>
    test(s"option combination ${options.separator} ${options.doubleIndent} ${options.simplifyNestedObjects}") {
      assertFormats(nested, expected, options)
    }
  }

  // The simplification chain, pinned by upstream's own suite: a single-field chain flattens, and
  // a comment, an include or a `+=` anywhere in it stops the flattening.
  test("simplifyNestedObjects flattens a single-field chain") {
    assertFormats("a {\n  b {\n    c = 1\n  }\n}\n", "a.b.c = 1\n")
  }

  test("a comment stops the simplification chain") {
    assertFormats("a {\n  # note\n  b = 1\n}\n", "a {\n  # note\n  b = 1\n}\n")
  }
}
