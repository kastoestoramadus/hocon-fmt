package ww86.hocon_fmt

import ww86.hocon_fmt.HoconFormatter.format

/** Coverage of the HOCON specification, as seen through `HoconFormatter.format`.
  *
  * Everything here exercises our pipeline, so every test is named `formatter:`. Where the
  * formatter refuses an input, the underlying fault is the library's, not ours - those defects
  * are reproduced against bare sconfig in [[SconfigDefectsSpec]]. Our only responsibility is to
  * notice and refuse rather than write corrupted output.
  *
  * Coverage of the HOCON specification.
  *
  * Two groups: constructs the formatter destroys (bugs, pinned so a fix shows up as a failure),
  * and normalisations it performs on purpose (pinned so they are not "fixed" by accident).
  *
  * @see
  *   https://github.com/lightbend/config/blob/main/HOCON.md
  */
class HoconSpecCoverageSpec extends munit.FunSuite with HoconTestSupport {

  // --- Never hand back output we cannot read again ---------------------------------------------
  // These constructs cannot survive the parse-render round trip. Returning Success with
  // unparseable output is the worst outcome available, because CmdApi writes on success:
  // rewrite mode would replace a valid config with a broken one. Refusing is correct.

  val mustRefuse = Map(
    "+= field separator"            -> "a : [1]\na += 2",
    "+= field separator, nested"    -> "o { a : [1]\na += 2 }",
    "self-referential substitution" -> "a : 1\na : ${a}",
    // From the specification's "Array and object concatenation" examples.
    "array self-concatenation" -> "a : [ 1, 2 ]\na : ${a} [ 3, 4 ]"
  )

  mustRefuse.foreach { case (name, raw) =>
    test(s"formatter: refuses rather than corrupts: $name") {
      assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
      refusalOf(raw) match {
        case Refusal.BrokenOutput(_) => ()
        case other                   => fail(s"$name: refused for the wrong reason: ${other.reason}")
      }
    }
  }

  // Taken verbatim from the HOCON specification. The cycle renders as an unresolved-merge
  // banner: it parses, so the output check passes, but a second pass changes it again.
  def specSelfReference = Map(
    "substitution cycle (Examples of Self-Referential Substitutions)" ->
      "a : 1\nb : 2\na : ${b}\nb : ${a}"
  )

  specSelfReference.foreach { case (name, raw) =>
    test(s"formatter: refuses output that is not a fixed point: $name") {
      assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
      assertEquals(refusalOf(raw), Refusal.UnstableOutput, s"$name: refused for the wrong reason")
    }
  }

  // --- Never lose a comment -------------------------------------------------------------------
  // A comment carries no meaning to compare, so neither the re-parse nor the fixed-point check can
  // notice one going missing. sconfig drops a comment that no field follows (see
  // SconfigDefectsSpec), so these must either keep every comment or be refused.

  def commentsOnly = Map(
    "after the last field"             -> ("a : 1\n# trailing", List("trailing")),
    "last in an object"                -> ("o {\n  a : 1\n  # last in the object\n}", List("last in the object")),
    "a file of nothing but comments"   -> ("# one\n// two\n", List("one", "two")),
    "after the last field, // comment" -> ("a : 1\n// trailing", List("trailing"))
  )

  commentsOnly.foreach { case (name, (raw, comments)) =>
    test(s"formatter: never loses a comment: $name") {
      format(raw) match {
        case Right(out)                   => comments.foreach(c => assert(out.contains(c), s"comment [$c] lost from: $out"))
        case Left(Refusal.LostComment(_)) => ()
        case Left(other)                  => fail(s"refused for the wrong reason: ${other.reason}")
      }
    }
  }

  // The commonest lost comment in the wild: a blank line between the comment and the field that
  // follows, as under a licence header or a banner, or inside a comment block split by one.
  // sconfig drops the comment outright (see SconfigDefectsSpec), so the only safe answer is to
  // refuse; 20 of 23 reference.conf files in the Akka, Pekko, Play, Kamon, Gatling and ssl-config
  // corpus hold such a comment.
  def commentAboveBlankLine = Map(
    "a header followed by a blank line"     -> "# Copyright 2025\n\na : 1",
    "a comment block split by a blank line" ->
      "o {\n  a : 1\n  # one\n\n  # two\n  b : 2\n}"
  )

  commentAboveBlankLine.foreach { case (name, raw) =>
    test(s"formatter: never loses a comment: $name") {
      assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
      refusalOf(raw) match {
        case Refusal.LostComment(_) => ()
        case other                  => fail(s"$name: refused for the wrong reason: ${other.reason}")
      }
    }
  }

  // --- Normalised on purpose: meaning kept, original spelling not ------------------------------

  def normalised = List(
    ("// comments become #", "// c\na : 1", "# c"),
    ("the default separator is =", "a : 1", "a = 1"),
    ("nested objects are flattened to paths", "a { b { c : 1 } }", "a.b.c = 1"),
    ("triple-quoted strings become escaped", "a : \"\"\"x\ny\"\"\"", "a = \"x\\ny\""),
    ("+= appends become the expanded substitution", "a += 2", "a = ${?a}["),
    ("number literals are canonicalised", "a : 1.5e3", "a = 1500"),
    ("unicode escapes are resolved", "a : \"\\u0041\"", "a = A")
  )

  normalised.foreach { case (name, raw, expectedFragment) =>
    test(s"formatter: normalised: $name") {
      val out = formatted(raw)
      assert(out.contains(expectedFragment), s"expected [$expectedFragment] in: $out")
      assertSameMeaning(out, raw, s"normalisation changed meaning: $raw")
    }
  }

  test("formatter: normalised: the separator is : on request") {
    assertEquals(HoconFormatter.format("a = 1", FormatOptions(separator = Separator.Colon)), Right("a: 1\n"))
    assertEquals(HoconFormatter.format("a : 1", FormatOptions(separator = Separator.Colon)), Right("a: 1\n"))
  }

  // --- Supported, guarded against regression ---------------------------------------------------

  def supported = Map(
    "substitution"           -> "b : 1\na : ${b}",
    "optional substitution"  -> "a : ${?MISSING}\nb : 2",
    "array concatenation"    -> "a : [1] [2]",
    "object concatenation"   -> "a : { x : 1 } { y : 2 }",
    "duplicate key merging"  -> "a { x : 1 }\na { y : 2 }",
    "path expression key"    -> "a.b.c : 1",
    "null and booleans"      -> "a : null\nb : true",
    "trailing commas"        -> "a : [1, 2, ]",
    "empty object and array" -> "a : {}\nb : []"
  )

  supported.foreach { case (name, raw) =>
    test(s"formatter: supported: $name") {
      val out = formatted(raw)
      assert(out.parses.isSuccess, s"output does not re-parse: $out")
      assertSameMeaning(out, raw, s"meaning changed for: $raw")
    }
  }
}
