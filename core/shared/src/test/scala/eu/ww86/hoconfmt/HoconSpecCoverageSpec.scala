package eu.ww86.hoconfmt

import eu.ww86.hoconfmt.HoconFormatter.format

/** Coverage of the HOCON specification, as seen through `HoconFormatter.format`.
  *
  * Everything here exercises our pipeline, so every test is named `formatter:`. Where the
  * formatter refuses an input, the underlying fault is the library's, not ours - those defects
  * are reproduced against bare sconfig in [[SconfigDefectsSpec]]. Our only responsibility is to
  * notice and refuse these defects. The accepted numeric rendering limitation is pinned in
  * [[NumericRenderingSpec]].
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
    "nested self-reference"         -> "foo : { a : 1 }\nfoo : ${foo.a}",
    "env override in an object"     -> "o {\n  host = localhost\n  host = ${?HOST}\n}",
    // A sibling after the enclosing object does not flip these: the banner still does not parse.
    "env override in an object with a sibling after the object" ->
      "o {\n  host = localhost\n  host = ${?HOST}\n}\nname = x",
    "self-reference in an object with a sibling after the object" -> "o {\n  a = 1\n  a = ${a}\n}\nname = x",
    "nested self-reference with a trailing field"                 -> "foo : { a : 1 }\nfoo : ${foo.a}\nname = app",
    // From the specification's "Array and object concatenation" examples.
    "array self-concatenation" -> "a : [ 1, 2 ]\na : ${a} [ 3, 4 ]"
  )

  mustRefuse.filterNot((name, _) => Variant.differs(s"mustRefuse: $name")).foreach { case (name, raw) =>
    test(s"formatter: refuses rather than corrupts: $name") {
      assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
      refusalOf(raw) match {
        case Refusal.BrokenOutput(_) => ()
        case other                   => fail(s"$name: refused for the wrong reason: ${other.reason}")
      }
    }
  }

  // Taken verbatim from the HOCON specification's self-referential example. Both keys are
  // defined first, and the cycle written over those definitions is an unresolved merge: it
  // renders as a banner that parses but changes again on the next pass. A cycle between bare
  // names, with no definition beside it, is a different shape — formatting settles it, and the
  // test below pins that, so the docs name this input where the refusal is described.
  def specSelfReference = Map(
    "substitution cycle (Examples of Self-Referential Substitutions)" ->
      "a : 1\nb : 2\na : ${b}\nb : ${a}"
  )

  specSelfReference.filterNot((name, _) => Variant.differs(s"specSelfReference: $name")).foreach { case (name, raw) =>
    test(s"formatter: refuses output that is not a fixed point: $name") {
      assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
      assertEquals(refusalOf(raw), Refusal.UnstableOutput, s"$name: refused for the wrong reason")
    }
  }

  // The pure cycle is no refusal at all: `a : ${b}` with `b : ${a}` formats to a fixed point,
  // and resolution fails with the same cycle complaint on the input and the output, so nothing
  // is hidden. docs/limitations.md carries this shape next to the two merge refusals above.
  test("formatter: formats a pure substitution cycle to a fixed point") {
    val out = formatted("a : ${b}\nb : ${a}")
    assertEquals(out, "a = ${b}\nb = ${a}\n")
    assertEquals(formatted(out), out, "the second pass must keep the output")
  }

  // The same defects with one ordinary field written after the merge: the banner sconfig then
  // renders parses again, so the output check passes and the second-pass check refuses instead.
  // Which refusal a merge earns is a fact about the shape, not the defect; docs/limitations.md
  // lists both verdicts with these inputs. The fork's base settles these merges, hence the
  // ledger.
  def mustRefuseUnstable = Map(
    "self-referential substitution with a sibling field" -> "a : 1\na : ${a}\nname = app",
    "+= field separator with a sibling field"            -> "a : [1]\na += 2\nname = app",
    "env override with a sibling field"                  -> "host = localhost\nhost = ${?HOST}\nname = app",
    "env override in an object with a sibling field"     -> "http {\n  host = \"127.0.0.1\"\n  host = ${?HOST}\n  port = 80\n}",
    // A field before the merge flips the verdict too, and so does a leading comment.
    "self-referential substitution with a field before"    -> "b : 2\na : 1\na : ${a}",
    "self-referential substitution with a leading comment" -> "# c\na : 1\na : ${a}"
  )

  mustRefuseUnstable.filterNot((name, _) => Variant.differs(s"unstableMerge: $name")).foreach { case (name, raw) =>
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

  // The other direction: sconfig's `+=` desugaring copies the comment of the appended field onto
  // both halves of the expansion (`a = ${?a}[` and the appended element), so a render of `# c`
  // above `a += 2` holds it twice. A duplicated comment is a phantom the author never wrote, and
  // it is refused the way a lost one is — SconfigDefectsSpec pins the library bug.
  List(
    "on an appended field"               -> "# c\na += 2",
    "on an appended field, in an object" -> "o {\n  # c\n  a += 2\n}"
  ).filterNot((name, _) => Variant.differs(s"duplicatedComment: $name")).foreach { case (name, raw) =>
    test(s"formatter: never duplicates a comment: $name") {
      assertEquals(refusalOf(raw), Refusal.LostComment("c"))
    }
  }

  // The commonest lost comment in the wild: a blank line between the comment and the field that
  // follows. Inside an object, where no preamble can stand, sconfig drops the comment outright
  // (see SconfigDefectsSpec), so the only safe answer is to refuse; 20 of 23 reference.conf files
  // in the Akka, Pekko, Play, Kamon, Gatling and ssl-config corpus hold such a comment. A block at
  // the top of the file, which the command line keeps as a preserved preamble and the page's fork
  // keeps through its carrier, is pinned by PreambleSpec and CommentCarrierSpec.
  def commentAboveBlankLine = Map(
    "a comment block split by a blank line" ->
      "o {\n  a : 1\n  # one\n\n  # two\n  b : 2\n}"
  )

  commentAboveBlankLine.filterNot((name, _) => Variant.differs(s"commentAboveBlankLine: $name")).foreach {
    case (name, raw) =>
      test(s"formatter: never loses a comment: $name") {
        assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
        refusalOf(raw) match {
          case Refusal.LostComment(_) => ()
          case other                  => fail(s"$name: refused for the wrong reason: ${other.reason}")
        }
      }
  }

  // The header the two variants keep differently: the command line takes the block verbatim — the
  // blank line included — while the fork renders the same comments through its carrier. The exact
  // output of each is pinned by its own suite (PreambleSpec, CommentCarrierSpec); here both must
  // keep the comment and the meaning.
  test("formatter: keeps a detached header: a header followed by a blank line") {
    val source = "# Copyright 2025\n\na : 1"
    val out    = formatted(source)
    assert(out.contains("# Copyright 2025"), out)
    assertSameMeaning(out, source, s"the header changed the meaning of:\n$source")
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
