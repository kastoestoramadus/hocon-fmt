package eu.ww86.hoconfmt

/** Our resource refusal boundary, independent of the parser's platform stack. */
class NestingDepthSpec extends munit.FunSuite {

  val limit  = 80
  val reason = s"input nesting exceeds the maximum depth of $limit"

  val pathLimit  = 120
  val pathReason = s"input dotted key paths exceed the maximum of $pathLimit segments"

  // The flattened path — the segments of every enclosing object plus the written ones — is what
  // the duplicate report's cost follows, so nesting re-creates from short lines what the
  // written-line limit above caps (a 20-level chain of 120-segment paths spent 34 seconds in the
  // report). The scan counts it: a `{` adds the key path just written to what every definition
  // inside inherits, and a `}` takes it back out. Pinned in the section at the bottom.
  val flatLimit  = 720
  val flatReason = s"input dotted key paths exceed the maximum of $flatLimit segments"

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

  // --- Flattened dotted key paths -------------------------------------------------
  //
  // The scan counts the flattened path: a `{` adds the key path just written to what every
  // definition inside inherits, and a `}` takes it back out.

  def objectPath(segments: Int): String = (1 to segments).map(i => s"k$i").mkString(".")
  def leafPath(segments: Int): String   = (1 to segments).map(i => s"l$i").mkString(".")

  /** `levels` nested objects, each reached by a dotted key path of `segments` segments, with a
    * leaf of `leafSegments` segments inside the deepest one (`0` writes the one-segment
    * `leaf = 1`). The deepest definition's flattened path is
    * `levels * segments + max(leafSegments, 1)`. A sibling per level keeps two fields in every
    * object, so the render flattens nothing: one long dotted line in the output would not
    * reparse on Scala.js.
    */
  def chain(levels: Int, segments: Int, leafSegments: Int): String =
    (objectPath(segments) + " { sibling = 0\n") * levels
      + (if (leafSegments == 0) "leaf = 1\n" else leafPath(leafSegments) + " = 1\n")
      + "}\n" * levels

  test("a chain at the flattened limit formats, one segment more refuses") {
    assert(HoconFormatter.format(chain(23, 30, 30)).isRight) // 23*30 + 30 = 720
    assertEquals(
      HoconFormatter.format(chain(24, 30, 0)).left.map(_.reason), // 24*30 + 1 = 721
      Left(flatReason)
    )
    Verdict.of(chain(24, 30, 0)) match {
      case Verdict.Refused(refusal) => assertEquals(refusal.reason, flatReason)
      case other                    => fail(s"expected a refused verdict, got $other")
    }
  }

  test("a 20-level chain of maximal written paths refuses with the flattened reason") {
    assertEquals(HoconFormatter.format(chain(20, 120, 0)).left.map(_.reason), Left(flatReason))
  }

  test("duplicate analysis refuses a flattened path over the limit before its document parse") {
    assertEquals(DuplicateReport.findings(chain(24, 30, 0)).left.map(_.reason), Left(flatReason))
  }

  test("the written-line limit still refuses first inside nesting") {
    // A 121-segment line inside a 120-segment object carries both counts over their limits;
    // the line cap names its own limit, not the flattened one.
    val source = objectPath(120) + " {\n" + leafPath(121) + " = 1\n}\n"
    assertEquals(HoconFormatter.format(source).left.map(_.reason), Left(pathReason))
  }

  test("closing an object takes its key path back out of the flattened count") {
    // 100 independent chains reach 11 flattened segments each; a `}` that failed to subtract
    // would pile them up and refuse the file around the 72nd line.
    val source = (1 to 100).map(i => s"${objectPath(9)} { leaf = $i }\n").mkString
    assert(HoconFormatter.format(source).isRight)
  }

  test("siblings inherit the enclosing path once, not once per sibling") {
    // Seven leaves inside a 711-segment chain sit at 712..718 flattened segments, still under
    // the limit; a scan that re-added the enclosing path per sibling would refuse them.
    assume(libraryParsesLongPaths, "Scala.js sconfig cannot parse 33-element paths yet")
    val source = (objectPath(79) + " {\n") * 9 + (1 to 7).map(i => s"leaf$i = $i\n").mkString + "}\n" * 9
    assert(HoconFormatter.format(source).isRight)
  }

  test("the corpus-maximum shape, depth 8 and path 11, formats") {
    assert(HoconFormatter.format(chain(8, 11, 0)).isRight) // 89 flattened segments
  }

  // The spelling of the value does not matter to the scan: whatever stands between the key path
  // and the brace — `=`, `:`, `+=`, blanks, a newline, a comment — the object inside inherits the
  // path just written. That is what the duplicate report counts (probed on sconfig: `a.b.c.x` in
  // every spelling, across a newline and a comment too, and `a.b.c[0].x` for the object inside an
  // array, siblings alike). Pinned at the boundary: 24 levels of 30 carry 721 flattened segments
  // and must refuse before the parser runs, 23 levels with a 30-segment leaf sit at 720 and must
  // survive. The paths stay at 30 segments, which the library parses on Scala.js too, and a
  // sibling per level keeps two fields in every object, so the render flattens nothing: one long
  // dotted line in the output would not reparse there.
  def chainIn(spelling: String, levels: Int, segments: Int, leafSegments: Int): String = {
    val opener = spelling match {
      case "brace"   => objectPath(segments) + " { sibling = 0\n"
      case "eq"      => objectPath(segments) + " = { sibling = 0\n"
      case "colon"   => objectPath(segments) + " : { sibling = 0\n"
      case "append"  => objectPath(segments) + " += { sibling = 0\n"
      case "blanks"  => objectPath(segments) + "  =  { sibling = 0\n"
      case "newline" => objectPath(segments) + " =\n{ sibling = 0\n"
      case "array"   => objectPath(segments) + " = [{ sibling = 0\n"
    }
    val closer = if (spelling == "array") "}]\n" else "}\n"
    opener * levels
      + (if (leafSegments == 0) "leaf = 1\n" else leafPath(leafSegments) + " = 1\n")
      + closer * levels
  }

  def spellings = List("brace", "eq", "colon", "append", "blanks", "newline", "array")

  for (spelling <- spellings) {
    test(s"a chain one over the flattened limit refuses in the `$spelling` spelling") {
      val source = chainIn(spelling, 24, 30, 0) // 24*30 + 1 = 721
      assertEquals(HoconFormatter.format(source).left.map(_.reason), Left(flatReason))
      Verdict.of(source) match {
        case Verdict.Refused(refusal) => assertEquals(refusal.reason, flatReason)
        case other                    => fail(s"expected a refused verdict, got $other")
      }
    }
  }

  for (spelling <- spellings) {
    test(s"a chain at the flattened limit passes the scan in the `$spelling` spelling") {
      // The `+=` spelling reaches its own parser limit (nested `+=` is an sconfig limitation the
      // scan must not be blamed for), so the scan alone is asserted on every spelling.
      assertEquals(HoconText.checkNesting(chainIn(spelling, 23, 30, 30)), Right(())) // 720
    }
  }

  for (spelling <- List("brace", "eq", "colon", "blanks", "newline", "array")) {
    test(s"a chain at the flattened limit formats in the `$spelling` spelling") {
      assert(HoconFormatter.format(chainIn(spelling, 23, 30, 30)).isRight)
    }
  }

  for (spelling <- List("eq", "colon", "append", "blanks", "newline", "array")) {
    test(
      s"duplicate analysis refuses a flattened path over the limit written with `$spelling` before its document parse"
    ) {
      assertEquals(DuplicateReport.findings(chainIn(spelling, 24, 30, 0)).left.map(_.reason), Left(flatReason))
    }
  }

  test("a value that is not an object lends its path to no later brace") {
    // The count dies with the statement: after `x.y.z = 1`, a `{` must push nothing, or this
    // at-limit shape would sit at 723 flattened segments and refuse.
    assert(HoconFormatter.format("x.y.z = 1\n" + chain(23, 30, 30)).isRight)
    assert(HoconFormatter.format("x.y.z = \"1\"\n" + chain(23, 30, 30)).isRight)
  }
}
