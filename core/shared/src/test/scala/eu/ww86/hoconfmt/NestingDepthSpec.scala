package eu.ww86.hoconfmt

/** Our resource refusal boundary, independent of the parser's platform stack. */
class NestingDepthSpec extends munit.FunSuite {

  val limit  = 80
  val reason = s"input nesting exceeds the maximum depth of $limit"

  def objects(depth: Int, include: Boolean): String =
    "a {" * depth + (if (include) "include \"missing.conf\"\nx=1" else "x=1") + "}" * depth

  for {
    include <- List(false, true)
    depth   <- List(limit - 1, limit)
  } {
    test(s"$depth nested objects format with include=$include") {
      // Two fields keep parents explicit, avoiding sconfig's JS long-dotted-path refusal.
      val source    = objects(depth, include).replace("a {", "a { sibling=0, ")
      val formatted = HoconFormatter.format(source)
      assert(formatted.isRight, formatted.toString)
    }
  }

  for (include <- List(false, true)) {
    test(s"one object over the limit refuses with include=$include") {
      val source = objects(limit + 1, include)
      HoconFormatter.format(source) match {
        case Left(refusal) => assertEquals(refusal.reason, reason)
        case Right(_)      => fail("expected a depth refusal")
      }
      Verdict.of(source) match {
        case Verdict.Refused(refusal) => assertEquals(refusal.reason, reason)
        case other                    => fail(s"expected a refused verdict, got $other")
      }
    }
  }

  test("nested arrays count toward the same limit") {
    assert(HoconFormatter.format("a=" + "[" * limit + "1" + "]" * limit).isRight)
    assertEquals(
      HoconFormatter.format("a=" + "[" * (limit + 1) + "1" + "]" * (limit + 1)).left.map(_.reason),
      Left(reason)
    )
  }

  test("objects and arrays share a depth budget, including an explicit root") {
    val source = "{a=" + "[" * (limit - 1) + "{x=1}" + "]" * (limit - 1) + "}"
    assertEquals(HoconFormatter.format(source).left.map(_.reason), Left(reason))
  }

  test("siblings do not accumulate depth") {
    val source = (1 to (limit + 1)).map(i => s"a$i { x=1 }").mkString("\n")
    assert(HoconFormatter.format(source).isRight)
  }

  test("delimiters inside quoted strings and comments are not nesting") {
    val braces = "{[" * (limit + 1)
    val source = s"# $braces\n\na = \"$braces\"\nb = \"\"\"$braces\"\"\"\n// $braces\nc=1\n"
    assert(HoconFormatter.format(source).isRight)
  }

  test("escaped quotes do not expose string delimiters") {
    val source = "a=\"escaped \\\"" + "{" * (limit + 1) + "\"\n"
    assert(HoconFormatter.format(source).isRight)
  }

  test("duplicate analysis refuses excessive nesting before its document parse") {
    assertEquals(DuplicateReport.findings(objects(limit + 1, include = true)).left.map(_.reason), Left(reason))
  }

  test("10000 nested objects refuse before parsing, with and without an include") {
    List(false, true).foreach { include =>
      Verdict.of(objects(10000, include)) match {
        case Verdict.Refused(refusal) => assertEquals(refusal.reason, reason)
        case other                    => fail(s"expected a refused verdict, got $other")
      }
    }
  }
}
