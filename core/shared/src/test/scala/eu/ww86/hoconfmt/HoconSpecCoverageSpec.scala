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
  * Through the document engine the file is kept as written: spelling, comments and unresolved
  * merges all survive, so most of what is pinned here is that keeping (a regression shows up as
  * a failure), plus the supported constructs that must not change meaning.
  *
  * @see
  *   https://github.com/lightbend/config/blob/main/HOCON.md
  */
class HoconSpecCoverageSpec extends munit.FunSuite with HoconTestSupport {

  // --- Unresolved merges are kept as written ----------------------------------------------------
  // The document engine resolves nothing, so every one of these shapes formats verbatim: the
  // output re-parses, means the same and is a fixed point. The resolving render behind the old
  // engine corrupted or destabilised them, which is why this group was once a refusal list
  // (the library defects behind those refusals stay pinned in SconfigDefectsSpec).

  val keptVerbatim = Map(
    "+= field separator"            -> "a : [1]\na += 2",
    "+= field separator, nested"    -> "o { a : [1]\na += 2 }",
    "self-referential substitution" -> "a : 1\na : ${a}",
    "nested self-reference"         -> "foo : { a : 1 }\nfoo : ${foo.a}",
    "env override in an object"     -> "o {\n  host = localhost\n  host = ${?HOST}\n}",
    // The documented shapes, each complete: the definitions the quoted snippet depends on are included.
    "array concatenation with a substitution"  -> "path = [ /bin ]\npath = ${path} [ /usr/bin ]",
    "string concatenation with a substitution" -> "path : \"/bin\"\npath : ${path}\":d\"",
    "object concatenation with a substitution" -> "g { x = 1 }\ne = ${g} { name = \"east\" }",
    "env override alone at the root"           -> "host = localhost\nhost = ${?HOST}",
    // A sibling after the enclosing object does not flip these: the banner still does not parse.
    "env override in an object with a sibling after the object" ->
      "o {\n  host = localhost\n  host = ${?HOST}\n}\nname = x",
    "self-reference in an object with a sibling after the object" -> "o {\n  a = 1\n  a = ${a}\n}\nname = x",
    "nested self-reference with a trailing field"                 -> "foo : { a : 1 }\nfoo : ${foo.a}\nname = app",
    // From the specification's "Array and object concatenation" examples.
    "array self-concatenation" -> "a : [ 1, 2 ]\na : ${a} [ 3, 4 ]"
  )

  // The ledger id keeps its historical `mustRefuse:` prefix: the site variant filters by it.
  keptVerbatim.filterNot((name, _) => Variant.differs(s"mustRefuse: $name")).foreach { case (name, raw) =>
    test(s"formatter: keeps as written: $name") {
      assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
      val out = formatted(raw)
      assert(out.parses.isSuccess, s"output does not re-parse: $out")
      assertSameMeaning(out, raw, s"formatting changed the meaning of: $raw")
      assertEquals(formatted(out), out, "the second pass must keep the output")
    }
  }

  // Taken verbatim from the HOCON specification's self-referential example. Both keys are
  // defined first, and the cycle written over those definitions is an unresolved merge: the
  // document engine keeps the file as written, so it formats at a fixed point and the cycle
  // still fails resolution identically on the input and the output — nothing is hidden.
  def specSelfReference = Map(
    "substitution cycle (Examples of Self-Referential Substitutions)" ->
      "a : 1\nb : 2\na : ${b}\nb : ${a}"
  )

  specSelfReference.filterNot((name, _) => Variant.differs(s"specSelfReference: $name")).foreach { case (name, raw) =>
    test(s"formatter: keeps a substitution cycle as written at a fixed point: $name") {
      assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
      val out = formatted(raw)
      assertEquals(out, "a = 1\nb = 2\na = ${b}\nb = ${a}\n", s"$name: wrong rendering")
      assertEquals(formatted(out), out, "the second pass must keep the output")
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

  // The same shapes with one ordinary field written around the merge: siblings, preceding
  // fields and leading comments change nothing any more — the document engine keeps every
  // statement as written, so each formats at a fixed point. The ledger id keeps its historical
  // `unstableMerge:` prefix for the site variant.
  def keptVerbatimWithSibling = Map(
    "self-referential substitution with a sibling field" -> "a : 1\na : ${a}\nname = app",
    "+= field separator with a sibling field"            -> "a : [1]\na += 2\nname = app",
    "env override with a sibling field"                  -> "host = localhost\nhost = ${?HOST}\nname = app",
    "env override in an object with a sibling field"     -> "http {\n  host = \"127.0.0.1\"\n  host = ${?HOST}\n  port = 80\n}",
    // A field before the merge and a leading comment are the same shape.
    "self-referential substitution with a field before"    -> "b : 2\na : 1\na : ${a}",
    "self-referential substitution with a leading comment" -> "# c\na : 1\na : ${a}",
    "env override with a field before"                     -> "z = 1\nhost = localhost\nhost = ${?HOST}",
    "env override with a leading comment"                  -> "# c\nhost = localhost\nhost = ${?HOST}"
  )

  keptVerbatimWithSibling.filterNot((name, _) => Variant.differs(s"unstableMerge: $name")).foreach {
    case (name, raw) =>
      test(s"formatter: keeps as written: $name") {
        assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
        val out = formatted(raw)
        assert(out.parses.isSuccess, s"output does not re-parse: $out")
        assertSameMeaning(out, raw, s"formatting changed the meaning of: $raw")
        assertEquals(formatted(out), out, "the second pass must keep the output")
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

  // The old engine's `+=` desugaring copied the comment of the appended field onto both halves
  // of the expansion, so a render of `# c` above `a += 2` held it twice and was refused. The
  // document engine keeps `+=` verbatim, so the comment appears exactly once
  // (SconfigDefectsSpec still pins the library bug the old path worked around).
  List(
    "on an appended field"               -> "# c\na += 2",
    "on an appended field, in an object" -> "o {\n  # c\n  a += 2\n}"
  ).filterNot((name, _) => Variant.differs(s"duplicatedComment: $name")).foreach { case (name, raw) =>
    test(s"formatter: renders an appended field's comment exactly once: $name") {
      val out = formatted(raw)
      assertEquals(out.count(_ == '#'), 1, s"[$name] the comment must appear once: $out")
    }
  }

  // The commonest lost comment in the wild: a blank line between the comment and the field that
  // follows. The old resolving render dropped it inside an object (see SconfigDefectsSpec), so
  // the only safe answer was a refusal; the document engine keeps the block, blank line and all.
  // 20 of 23 reference.conf files in the Akka, Pekko, Play, Kamon, Gatling and ssl-config corpus
  // hold such a comment. A block at the top of the file is pinned by PreambleSpec and
  // CommentCarrierSpec.
  def commentAboveBlankLine = Map(
    "a comment block split by a blank line" ->
      "o {\n  a : 1\n  # one\n\n  # two\n  b : 2\n}"
  )

  commentAboveBlankLine.filterNot((name, _) => Variant.differs(s"commentAboveBlankLine: $name")).foreach {
    case (name, raw) =>
      test(s"formatter: never loses a comment: $name") {
        assert(raw.parses.isSuccess, s"the fixture itself must be valid HOCON: $raw")
        val out = formatted(raw)
        assert(out.contains("# one"), s"comment [# one] lost from: $out")
        assert(out.contains("# two"), s"comment [# two] lost from: $out")
        assertSameMeaning(out, raw, s"formatting changed the meaning of: $raw")
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

  // --- Kept as written: meaning kept, original spelling kept ------------------------------------

  def keptAsWritten = List(
    ("// comment spelling stays",            "// c\na : 1", "// c"),
    ("the separator is normalised to the option (default =)", "a : 1", "a = 1"),
    ("nested objects are flattened to paths", "a { b { c : 1 } }", "a.b.c = 1"),
    ("triple-quoted strings stay triple-quoted", "a : \"\"\"x\ny\"\"\"", "\"\"\""),
    ("+= stays +=",                          "a += 2", "a += 2"),
    ("number literals keep their spelling",  "a : 1.5e3", "1.5e3"),
    ("unicode escapes stay as written",      "a : \"\\u0041\"", "\\u0041")
  )

  keptAsWritten.foreach { case (name, raw, expectedFragment) =>
    test(s"formatter: kept as written: $name") {
      val out = formatted(raw)
      assert(out.contains(expectedFragment), s"expected [$expectedFragment] in: $out")
      assertSameMeaning(out, raw, s"formatting changed the meaning of: $raw")
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
