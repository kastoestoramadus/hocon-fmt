package eu.ww86.hoconfmt

/** The published core's preserved preamble, which `coreSite` does not use: a leading block of
  * comments and blank lines that a blank line separates from the body is taken verbatim, before
  * anything is parsed, and put back in front of the formatted body. Released sconfig drops such a
  * block (`Refusal.LostComment`), and nearly every real file opens with one: a licence, an SPDX
  * line, a banner.
  *
  * The rule is narrow on purpose. The preamble is the longest run of blank and comment-only lines
  * that the file opens with, without the comment block that sits directly above the first line of
  * the body: that block is attached by the parse to the field below it and must not move. What is
  * left must hold a comment, so a file that opens with blank lines only has no preamble; a file
  * of nothing but comments has no body to format, and the document engine keeps it as written.
  *
  * The page's fork keeps the same comments through its `CommentCarrier` instead, normalised and
  * without the blank lines; `CommentCarrierSpec` pins that output there. Comments the preamble
  * does not cover are the document engine's now, kept where they stand; only the preamble block
  * itself is byte-exact.
  */
class PreambleSpec extends munit.FunSuite with HoconTestSupport {

  // The exact output, byte for byte: the block is the file's own text, marker, trailing spaces and
  // blank lines included. `//` stays `//` and the spacing of the header is untouched, while the
  // body takes the normalisations the formatter always makes.
  test("a detached header is kept verbatim") {
    val source = "// licence\n\n\n# second\n\na  =  1\n"
    assertEquals(formatted(source), "// licence\n\n\n# second\n\na = 1\n")
  }

  test("blank lines the file opens with are part of the preamble") {
    assertEquals(formatted("\n\n# head\n\na = 1\n"), "\n\n# head\n\na = 1\n")
  }

  test("a preamble-only change leaves the file already formatted") {
    assertEquals(Verdict.of("# Copyright 2025\n\na = 1\n"), Verdict.AlreadyFormatted)
  }

  // The block above the first field is the parse's own to attach; taking it would move it away from
  // the field and lose the attachment.
  test("a header directly above the first field stays attached, with no blank line added") {
    assertEquals(formatted("# Copyright 2025\na : 1\n"), "# Copyright 2025\na = 1\n")
  }

  // Only the block that a blank line detaches is the preamble's; the block that lands on the first
  // field stays with it.
  test("the comment block directly above the body stays with the body") {
    assertEquals(formatted("# head\n\n# section\na = 1\n"), "# head\n\n# section\na = 1\n")
  }

  test("a file of comments only has no body, so no preamble, and is kept as written") {
    assertEquals(HoconFormatter.format("# one\n# two\n"), Right("# one\n# two\n"))
    assertEquals(HoconFormatter.format("# only\n\n"), Right("# only\n"))
  }

  test("a file that opens with blank lines only has no preamble") {
    assertEquals(formatted("\n\na = 1\n"), "a = 1\n")
  }

  test("a braced root keeps its header and its braces") {
    // the document engine keeps the braces a braced root was written with; the resolving render
    // unwrapped them
    assertEquals(formatted("# head\n\n{ a : 1 }\n"), "# head\n\n{ a = 1 }\n")
  }

  test("a body with an include formats with its header") {
    assertEquals(
      formatted("# head\n\n# why\ninclude \"x.conf\"\na : 1\n"),
      "# head\n\n# why\ninclude \"x.conf\"\na = 1\n"
    )
  }

  test("formatting a preamble file keeps its meaning") {
    val source = "# head\n\n# section\na : 1\nb : ${a}\n"
    assertSameMeaning(formatted(source), source, s"the preamble path changed the meaning of:\n$source")
  }

  // The body is read in the file's own lines: the preamble is replaced, line for line, by blank
  // lines before anything is parsed, so a refusal that names a line names the file's line.
  // The shadow refusal this once used as its vehicle named the body's lines (includeLine 3,
  // definitionLine 4); it is gone with the include-shadow check, and no remaining refusal carries
  // line numbers, so the preamble's line arithmetic is pinned by the tests above through the
  // refusals that remain.

  // What the preamble path does not cover: the document engine's own comment keeping. These were
  // refusals while the resolving render dropped the comments; they are output pins now, and the
  // comments stand where the file put them.
  test("comments elsewhere in the file are kept where they stand") {
    assertEquals(HoconFormatter.format("a = 1\n# mid\n\nb = 2\n"), Right("a = 1\n# mid\n\nb = 2\n"))
    assertEquals(HoconFormatter.format("a = 1\n# tail\n"), Right("a = 1\n# tail\n"))
    assertEquals(HoconFormatter.format("o {\n  a = 1\n  # last\n}\n"), Right("o {\n  a = 1\n  # last\n}\n"))
    assertEquals(HoconFormatter.format("a = [\n  1\n  # tail\n]\n"), Right("a = [\n  1\n  # tail\n]\n"))
  }
}
