package eu.ww86.hoconfmt

/** Our resource refusal boundary, independent of the parser's platform stack. */
class NestingDepthSpec extends munit.FunSuite {

  val limit  = 80
  val reason = s"input nesting exceeds the maximum depth of $limit"

  val pathLimit  = 120
  val pathReason = s"input dotted key paths exceed the maximum of $pathLimit segments"

  def dotted(segments: Int): String = (1 to segments).map(i => s"k$i").mkString(".") + " = 1\n"

  // The library cannot parse a path of 33 or more elements on Scala.js yet
  // (scala-js/scala-js#5439, merged for 1.23.0, not released on 2026-10-14), so the
  // just-under-the-limit shapes are refused there before our guard has any say.
  val libraryParsesLongPaths = System.getProperty("java.vm.name", "") != "Scala.js"

  val substitutions = List(
    "plain"            -> "y=${z}",
    "optional"         -> "y=${?z}",
    "quoted key"       -> "y=${\"a.b\"}",
    "quoted brace key" -> "y=${\"a{\".b}",
    "concatenation"    -> "y=a ${x} b ${?w}"
  )

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

  def withSubstitution(depth: Int, field: String): String =
    // A sibling per level keeps parents explicit, avoiding sconfig's JS long-dotted-path refusal.
    "a { s=0\n" * depth + "x=1\n" + field + "\n" + "}" * depth

  for ((name, field) <- substitutions) {
    test(s"a $name substitution is not nesting: $limit levels format, one more refuses") {
      val formatted = HoconFormatter.format(withSubstitution(limit, field))
      assert(formatted.isRight, formatted.toString)
      assertEquals(
        HoconFormatter.format(withSubstitution(limit + 1, field)).left.map(_.reason),
        Left(reason)
      )
    }
  }

  test("a substitution at any level leaves the depth count unchanged") {
    def at(level: Int, depth: Int): String =
      (1 to depth).map(i => "a {" + (if (i == level) "y=${z}\n" else "")).mkString + "x=1" + "}" * depth
    for (level <- 1 to limit) {
      assertEquals(HoconText.checkNesting(at(level, limit)), Right(()), s"level $level")
      assert(HoconText.checkNesting(at(level, limit + 1)).isLeft, s"level $level")
    }
  }

  test("a substitution with a substitution-like brace inside a string or comment is still skipped") {
    val source = "a { s=0\n" * limit + "x=\"${\"\ny=${z} # ${\n" + "}" * limit
    assert(HoconFormatter.format(source).isRight)
  }

  test("10000 nested objects refuse before parsing, with and without an include") {
    List(false, true).foreach { include =>
      Verdict.of(objects(10000, include)) match {
        case Verdict.Refused(refusal) => assertEquals(refusal.reason, reason)
        case other                    => fail(s"expected a refused verdict, got $other")
      }
    }
  }

  // --- Long dotted key paths ------------------------------------------------------

  test("a dotted path one under the limit formats, with and without an include") {
    assume(libraryParsesLongPaths, "Scala.js sconfig cannot parse 33-element paths yet")
    List(false, true).foreach { include =>
      val source = (if (include) "include \"missing.conf\"\n" else "") + dotted(pathLimit - 1)
      assert(HoconFormatter.format(source).isRight, source.take(80))
    }
  }

  for (include <- List(false, true)) {
    test(s"a dotted path one over the limit refuses with include=$include") {
      val source = (if (include) "include \"missing.conf\"\n" else "") + dotted(pathLimit + 1)
      HoconFormatter.format(source) match {
        case Left(refusal) => assertEquals(refusal.reason, pathReason)
        case Right(_)      => fail("expected a dotted-path refusal")
      }
      Verdict.of(source) match {
        case Verdict.Refused(refusal) => assertEquals(refusal.reason, pathReason)
        case other                    => fail(s"expected a refused verdict, got $other")
      }
    }
  }

  test("duplicate analysis refuses a dotted path over the limit before its document parse") {
    assertEquals(DuplicateReport.findings(dotted(pathLimit + 1)).left.map(_.reason), Left(pathReason))
  }

  test("whitespace around the dots still counts segments: at the limit formats, one more refuses") {
    assume(libraryParsesLongPaths, "Scala.js sconfig cannot parse 33-element paths yet")
    def spaced(segments: Int): String = List.fill(segments)("a").mkString(" . ") + " = 1\n"
    assert(HoconFormatter.format(spaced(pathLimit)).isRight)
    assertEquals(HoconFormatter.format(spaced(pathLimit + 1)).left.map(_.reason), Left(pathReason))
  }

  test("dots inside one quoted key are one segment, however many") {
    val source = "\"" + (1 to 300).map(i => s"k$i").mkString(".") + "\" = 1\n"
    assert(HoconFormatter.format(source).isRight, source.take(80))
  }

  test("dots in a quoted value or an include target are not a key path") {
    val dottedName = (1 to 300).map(i => s"k$i").mkString(".")
    val value      = s"a = \"$dottedName\"\n"
    val include    = s"include \"$dottedName.conf\"\nx = 1\n"
    assert(HoconFormatter.format(value).isRight, value.take(80))
    assert(HoconFormatter.format(include).isRight, include.take(80))
  }

  test("a key of many space-separated words is one segment, not one per word") {
    assume(libraryParsesLongPaths, "Scala.js sconfig refuses this shape itself today")
    val source = List.fill(150)("k").mkString(" ") + " = 1\n"
    assert(HoconFormatter.format(source).isRight, source.take(80))
  }

  test("sibling dotted paths do not accumulate") {
    val source = (1 to 300).map(i => s"a$i.b = $i").mkString("\n") + "\n"
    assert(HoconFormatter.format(source).isRight)
  }
}
