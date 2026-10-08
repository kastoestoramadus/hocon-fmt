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
    assertEquals(out, "# Copyright 2025\na: 1\n")
  }

  test("every ledger entry now formats") {
    assertEquals(Variant.ledger.size, 14)
    List("catalogue/detached-header-comment", "catalogue/trailing-comment-in-object").foreach { id =>
      Verdict.of(example(id).input) match {
        case Verdict.NeedsFormatting(out) => assert(out.contains("#"), out)
        case other                        => fail(s"$id: $other")
      }
    }
    // The two coverage fixtures; the block split by a blank line keeps both halves, in order.
    assertEquals(
      stable("o {\n  a : 1\n  # one\n\n  # two\n  b : 2\n}"),
      "o {\n  a: 1\n  # one\n  # two\n  b: 2\n}\n"
    )
  }

  test("a trailing comment in an object is restored in place") {
    val out = stable("o {\n  a : 1\n  # last\n}")
    assertEquals(out, "o {\n  a: 1\n  # last\n}\n")
  }

  test("a trailing comment at the end of the file is restored") {
    assertEquals(stable("a : 1\n# the end\n"), "a: 1\n# the end\n")
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
  // like a placeholder. Neither the field nor a lookalike of the guard may be touched.
  test("text that looks like a placeholder is kept as it is") {
    List(
      "__COMMENT_0 : \"__COMMENT_0\"\n__COMMENT_GUARD_0 : \"g\"\nb : 1\n# end\n",
      "o {\n  \"__COMMENT_0\" : \"__COMMENT_0\"\n  b : 1\n  # end\n}",
      "__COMMENT_0 : \"__COMMENT_01\"\nb : 1\n# end\n"
    ).foreach { source =>
      HoconFormatter.format(source) match {
        case Right(out) =>
          assert(out.contains("# end"), out)
          assertEquals(HoconText.comments(out).count(_.contains("COMMENT")), 0, out)
          assert(out.contains("__COMMENT_0"), out)
          assert(out.contains("b: 1"), out)
        case Left(_) => () // refusing is allowed; altering is not
      }
    }
  }

  test("a comment that quotes a placeholder is kept") {
    val out = stable("a : 1\n# __COMMENT_0 : \"__COMMENT_0\"\n")
    assertEquals(out, "a: 1\n# __COMMENT_0 : \"__COMMENT_0\"\n")
  }
}
