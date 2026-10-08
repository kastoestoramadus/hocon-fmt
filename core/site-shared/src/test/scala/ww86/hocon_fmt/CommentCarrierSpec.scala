package ww86.hocon_fmt

class CommentCarrierSpec extends munit.FunSuite with HoconTestSupport {

  private def example(id: String): Example =
    ExampleData.all.find(_.id == id).getOrElse(fail(s"no example $id"))

  private def kindOf(refusal: Refusal): String = new ExamplesSpec().kindOf(refusal)

  /** Formatted, and formatting that again changes nothing. */
  private def stable(source: String)(using munit.Location): String = {
    val out = formatted(source)
    assertEquals(formatted(out), out, "not a fixed point")
    out
  }

  test("the option is on") {
    assert(HoconFormatter.parseOptions.getKeepDetachedComments)
  }

  test("a header above a blank line is kept") {
    val out = stable("# Copyright 2025\n\na : 1")
    assertEquals(out, "# Copyright 2025\na = 1\n")
  }

  test("the comment cases of the ledger now format") {
    assertEquals(Variant.ledger.size, 14)
    List("catalogue/detached-header-comment", "catalogue/trailing-comment-in-object").foreach { id =>
      Verdict.of(example(id).input) match {
        case Verdict.Refused(refusal) => fail(s"$id: ${refusal.reason}")
        case _                        => ()
      }
    }
    // The two coverage fixtures; the block split by a blank line keeps both halves, in order.
    assertEquals(
      stable("o {\n  a : 1\n  # one\n\n  # two\n  b : 2\n}"),
      "o {\n  a = 1\n  # one\n  # two\n  b = 2\n}\n"
    )
  }

  test("a trailing comment in an object is restored in place") {
    val out = stable("o {\n  a : 1\n  # last\n}")
    assertEquals(out, "o {\n  a = 1\n  # last\n}\n")
  }

  test("a trailing comment at the end of the file is restored") {
    assertEquals(stable("a : 1\n# the end\n"), "a = 1\n# the end\n")
  }

  test("a file of comments only is restored") {
    assertEquals(stable("# one\n# two\n"), "# one\n# two\n")
  }

  test("a comment above an include survives with it") {
    val out = stable("# why\n\ninclude \"x.conf\"\na : 1")
    assert(out.contains("# why"), out)
    assert(out.contains("include \"x.conf\""), out)
  }

  test("a comment at the end of an array is still refused") {
    assertEquals(HoconFormatter.format("a : [\n  1\n  # tail\n]"), Left(Refusal.LostComment("tail")))
  }

  test("a ledger example no longer ends as its pinned outcome") {
    Variant.ledger.keys.filter(_.contains("/")).foreach { id =>
      val actual = Verdict.of(example(id).input) match {
        case Verdict.NeedsFormatting(_) => "formatted"
        case Verdict.AlreadyFormatted   => "already-formatted"
        case Verdict.Refused(r)         => s"refused:${kindOf(r)}"
      }
      assertNotEquals(actual, example(id).now, id)
    }
  }

  // PR #54's review found the same hole in the include guards: restoring text that only looks
  // like a placeholder. The comment carrier's prefix steps aside for every spelling the parse
  // could put back together — quoted pieces concatenated, `\uXXXX` escapes resolved — so the
  // user's field is kept and the file still formats.
  test("text that could be spelled into a placeholder is kept, not restored") {
    val cases = List(
      ("literal field", "__COMMENT_0 : \"__COMMENT_0\"", "__COMMENT_0"),
      ("literal guard", "__COMMENT_GUARD_0 : \"g\"", "__COMMENT_GUARD_0"),
      ("another index", "__COMMENT_0 : \"__COMMENT_01\"", "__COMMENT_01"),
      ("concatenated value", "a : \"__COMM\"\"ENT_0\"", "__COMMENT_0"),
      ("concatenated key and value", "\"__COMM\"\"ENT_0\" : \"__COMM\"\"ENT_0\"", "__COMMENT_0"),
      ("concatenated guard", "\"__COMM\"\"ENT_GUARD_0\" : g", "__COMMENT_GUARD_0"),
      ("escaped underscores", "a : \"\\u005f\\u005fCOMMENT_0\"", "__COMMENT_0"),
      ("escaped guard", "a : \"\\u005f\\u005fCOMMENT_GUARD_0 = g\"", "__COMMENT_GUARD_0")
    )
    cases.foreach { case (name, user, kept) =>
      val source = user + "\nb : 1\n# end\n"
      HoconFormatter.format(source) match {
        case Right(out) =>
          assert(out.contains(kept), s"$name: [$kept] changed in:\n$out")
          assert(out.contains("# end"), s"$name: the comment was lost:\n$out")
        case Left(_) => () // refusing is allowed; altering is not
      }
    }
  }

  test("the prefix steps over a spelling the text could be rendered from") {
    val masked = CommentCarrier.mask("a : \"__COMM\"\"ENT_0\"\nb : 1\n# end\n").text
    assert(masked.contains("__COMMENTX_0"), masked)
  }

  test("a comment that quotes a placeholder is kept") {
    val out = stable("a : 1\n# __COMMENT_0 : \"__COMMENT_0\"\n")
    assertEquals(out, "a = 1\n# __COMMENT_0 : \"__COMMENT_0\"\n")
  }

  // The safety net behind the prefix choice: a render carrying one prefix occurrence more than
  // the placeholders wrote is left as it is. The comment is then missing, and the formatter's
  // lost-comment check refuses the file — never a restore on a guess.
  test("a render with a prefix occurrence that is not ours is left unrestored") {
    val carried  = CommentCarrier.mask("a = 1\n# end\n")
    val rendered = "a = 1\n__COMMENT_0 = \"__COMMENT_0\", __COMMENT_GUARD_0 = \"g\"\n"
    assertEquals(carried.restore(rendered), "a = 1\n# end\n")
    val extra = rendered + "__COMMENT_0 = 5\n"
    assertEquals(carried.restore(extra), extra)
  }
}
