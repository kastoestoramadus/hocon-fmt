package eu.ww86.hoconfmt

/** An `include` stands for a file the formatter cannot read: on a web page there is no filesystem,
  * and the target may be a URL. The mask therefore parses the text as if the included file said
  * nothing, and a definition a later one replaces is dropped from the output — while the text it
  * stood in for is put back. The dropped definition may have been exactly what kept the included
  * file's values out of a path, so the output would resolve differently.
  *
  * The two cases below are the ones the formatter research found in configs in the wild
  * (`docs/limitations.md`, B1 and B2). Refusing is the only safe answer, since the included file's
  * contents are unknown at format time.
  */
class IncludeShadowSpec extends munit.FunSuite with HoconTestSupport {

  import IncludeShadowSpec.*

  // B1: `f.conf` holds `o { retained = 9 }`. `o = 3` erases the included object and `o.c = 7`
  // replaces the scalar, so the file resolves o to `{ c = 7 }`; without line 2 the included
  // `retained` merges into `o.c = 7`.
  val b1 = "include \"f.conf\"\no=3\no.c=7\n"

  // B2: `scalar.conf` holds `x = 3`. `x {}` replaces the scalar, so the file resolves x to `{}`;
  // without line 3 the include's scalar is what x resolves to.
  val b2 = "x.a=5\ninclude \"scalar.conf\"\nx {}\n"

  test("B1: refuses the definition the include would reach once it is dropped") {
    val reason = refusalOf(b1).reason
    assert(reason.contains("line 2"), reason)
    assert(reason.contains("line 1"), reason)
    assert(reason.contains(" o"), reason)
  }

  test("B2: refuses the empty object the include's scalar was replaced by") {
    val reason = refusalOf(b2).reason
    assert(reason.contains("line 3"), reason)
    assert(reason.contains("line 2"), reason)
    assert(reason.contains(" x"), reason)
  }

  // B1 spelled with a path: `g.conf` holds `a.o { retained = 9 }`, and the two lines do to `a.o`
  // what the two lines above do to `o`. A dotted path also writes the objects on the way to its
  // leaf, on the same line, so whether one of those survived says nothing about the leaf.
  val b1WithPaths = List(
    ("dotted", "include \"g.conf\"\na.o = 3\na.o.c = 7\n", "a.o"),
    ("nested", "include \"g.conf\"\na { o = 3 }\na.o.c = 7\n", "a.o"),
    ("three segments", "include \"g.conf\"\na.o.b = 3\na.o.b.c = 7\n", "a.o.b"),
    ("nested path, dotted leaf", "include \"g.conf\"\na.o { b = 3 }\na.o.b.c = 7\n", "a.o.b")
  )

  test("B1 with a multi-segment path: refused however the path is spelled") {
    b1WithPaths.foreach { case (name, text, path) =>
      val reason = refusalOf(text).reason
      assert(reason.contains(s"out of $path"), s"$name: $reason")
      assert(reason.contains("line 2"), s"$name: $reason")
      assert(reason.contains("line 1"), s"$name: $reason")
    }
  }

  test("B2 with a multi-segment path: the empty object is refused") {
    val reason = refusalOf("a.o.x = 5\ninclude \"scalar.conf\"\na.o {}\n").reason
    assert(reason.contains("out of a.o"), reason)
    assert(reason.contains("line 3"), reason)
    assert(reason.contains("line 2"), reason)
  }

  test("the include's object gives the path in the reason") {
    val nested = "a {\n  include \"f.conf\"\n  o=3\n  o.c=7\n}\n"
    assert(refusalOf(nested).reason.contains("a.o"), refusalOf(nested).reason)
  }

  // Two definitions written on one line carry one origin line between them, and a value of the
  // merged tree carries a path and a line, never a column: the kept definition's own line is what
  // the dropped one's test reads. A file written this way must be refused like the two-line
  // spellings above, including where the definitions share the include's line, whose order the
  // rendering does not keep.
  val b1OnOneLine = List(
    ("at one key", "include \"f.conf\"\no = 3, o.c = 7\n", "o", 1, 2),
    ("with a dotted path", "include \"g.conf\"\na.o = 3, a.o.c = 7\n", "a.o", 1, 2),
    ("with a nested definition", "include \"g.conf\"\na { o = 3 }, a.o.c = 7\n", "a.o", 1, 2),
    ("with the path written twice", "include \"g.conf\"\na.o = 3, a.o = { c = 7 }\n", "a.o", 1, 2),
    ("inside an object", "a {\n  include \"g.conf\"\n  o = 3, o.c = 7\n}\n", "a.o", 2, 3),
    ("in a file with no newline", "include \"g.conf\", o = 3, o.c = 7", "o", 1, 1)
  )

  test("B1 with both definitions on one line: refused however the path is spelled") {
    b1OnOneLine.foreach { case (name, text, path, includeLine, definitionLine) =>
      val reason = refusalOf(text).reason
      assert(reason.contains(s"out of $path"), s"$name: $reason")
      assert(reason.contains(s"line $definitionLine"), s"$name: $reason")
      assert(reason.contains(s"line $includeLine"), s"$name: $reason")
    }
  }

  // A concatenation's pieces count their own positions: the second piece of `rows=[0] [{...}]`
  // counts no element for the first one, while the merged list does. A definition inside the later
  // piece therefore stands at an element the document parse numbers from zero and the merge numbers
  // after the piece before it, and a lookup by the document's own path reads the wrong element —
  // here the scalar `0`, which says nothing about the object the merge put at `q`.
  val arrayConcatenations = List(
    ("an object piece before", "rows=[{q=22}] [{\ninclude \"f.conf\"\nq=false\nq.child=8\n}]\n"),
    ("a scalar piece before", "rows=[0] [{\ninclude \"f.conf\"\nq=false\nq.child=8\n}]\n"),
    ("a two-element piece before", "rows=[0,1] [{\ninclude \"f.conf\"\nq=null, q.child=8\n}]\n"),
    ("two pieces before", "rows=[0] [1] [{\ninclude \"f.conf\"\nq=[2,3]\nq.child=8\n}]\n")
  )

  test("a definition in a later piece of a concatenation: refused by the path it really stands at") {
    arrayConcatenations.foreach { case (name, text) =>
      val reason = refusalOf(text).reason
      assert(reason.contains("out of rows[0].q"), s"$name: $reason")
      assert(reason.contains("line 3"), s"$name: $reason")
      assert(reason.contains("line 2"), s"$name: $reason")
    }
  }

  // The same shapes as an ordinary config writes them: an array concatenation whose second piece
  // holds an empty section beside its leaf. The definition stands at the element its own piece
  // counts, which the merged list numbers after the piece before it, so the refusal stands.
  val ordinaryArrayConcatenations = List(
    (
      "an empty object before its leaf",
      "servers=[{host=\"one\"}] [{\ninclude \"f.conf\"\npool {}\npool.size=8\n}]\n",
      "servers[0].pool"
    ),
    (
      "an empty object before a dotted leaf",
      "servers=[{host=\"one\"}] [{\ninclude \"f.conf\"\nheaders {}\nheaders.Accept=\"application/json\"\n}]\n",
      "servers[0].headers"
    )
  )

  test("an ordinary array concatenation: refused by the position the piece counts") {
    ordinaryArrayConcatenations.foreach { case (name, text, path) =>
      val reason = refusalOf(text).reason
      assert(reason.contains(s"out of $path"), s"$name: $reason")
      assert(reason.contains("line 3"), s"$name: $reason")
      assert(reason.contains("line 2"), s"$name: $reason")
    }
  }

  test("a concatenation's dropped empty object: refused, object piece or array piece") {
    concatenationHazards.foreach { case (name, text, path, definitionLine, includeLine) =>
      refusalOf(text) match {
        case refusal: Refusal.ShadowedByInclude =>
          assertEquals(refusal.path, path, name)
          assertEquals(refusal.definitionLine, definitionLine, name)
          assertEquals(refusal.includeLine, includeLine, name)
        case other => fail(s"$name: expected a shadowed-definition refusal, got $other")
      }
    }
  }

  test("an object concatenation's ordinary shapes: refused, a documented over-refusal") {
    objectConcatenationsOverRefused.foreach { case (name, text, path, definitionLine, includeLine) =>
      refusalOf(text) match {
        case refusal: Refusal.ShadowedByInclude =>
          assertEquals(refusal.path, path, name)
          assertEquals(refusal.definitionLine, definitionLine, name)
          assertEquals(refusal.includeLine, includeLine, name)
        case other => fail(s"$name: expected a shadowed-definition refusal, got $other")
      }
    }
  }

  test("the shapes the refusal protects, without a dropped definition, still format") {
    // A definition before the include is folded into one value before the include reads it, and a
    // later definition wins over it; only a definition the formatting drops can let values
    // through. A dropped definition the include does not stand before is harmless however it is
    // spelled: the include's own values are put in after it, and a later definition dominates
    // them the same way with or without it.
    val stillFormats = List(
      "a scalar before the include"           -> "o=3\ninclude \"f.conf\"\no.c=7\n",
      "the include after the definitions"     -> "o=3\no.c=7\ninclude \"f.conf\"\n",
      "a later scalar erases the include"     -> "include \"f.conf\"\no=3\no=4\n",
      "a later null erases the include"       -> "include \"f.conf\"\no=3\no=null\n",
      "an array replaces the include"         -> "include \"f.conf\"\no=3\no=[1,2]\n",
      "two objects merge"                     -> "include \"f.conf\"\no={a=1}\no={c=7}\n",
      "a scalar replaces the object"          -> "include \"f.conf\"\no={a=1}\no=3\n",
      "the include writes another object"     -> "a {\n  include \"f.conf\"\n}\nb { o=3\no.c=7 }\n",
      "an empty object first"                 -> "x {}\ninclude \"scalar.conf\"\n",
      "the definitions after the include"     -> "x {}\ninclude \"scalar.conf\"\nx.a=5\n",
      "dotted definitions before the include" -> "a.o = 3\na.o.c = 7\ninclude \"g.conf\"\n",
      // Nothing is dropped: the later scalar erases the earlier one the same way with or without
      // it, so the include's own value never reaches a path the output leaves open.
      "a concatenated array with nothing dropped" -> "rows=[0] [{\ninclude \"f.conf\"\nq=1\nq=2\n}]\n"
    )
    stillFormats.foreach { case (name, text) =>
      val formatted = HoconFormatter.format(text).fold(refusal => fail(s"$name: ${refusal.reason}"), identity)
      assertEquals(HoconFormatter.format(formatted), Right(formatted), name)
    }
  }

  test("deleting the dropped line makes the file format") {
    assertEquals(
      HoconFormatter.format("include \"f.conf\"\no.c=7\n"),
      Right("include \"f.conf\"\no.c = 7\n")
    )
  }
}

object IncludeShadowSpec {

  /** An independent review's counterexamples, all eighteen: an object concatenation whose last
    * piece ends in an empty object beside the include. The empty object is what clears the value the
    * include left at the path, so dropping it — and the merge does, since it adds nothing to the
    * object it merges into — lets the included value through. `app={} {q=0, q.a=1, include "f.conf",
    * q {}}` came out `app { q.a = 1, include "f.conf" }`, which resolves `app.q` to 9 where the
    * input resolves `{}`. The reviewer resolved each source against a real file with the bodies
    * `q=9`, `q=null`, `q=[9]` and `q={retained=91,a={nested=92}}`: 74 comparisons, every one
    * different. Nothing inside a concatenation can vouch for a definition beside the include — two
    * objects merging under one name count no element, but the merged tree holds one value at the
    * shared path and line, and which of the definitions put it there cannot be read off it — so the
    * doubt covers the piece whole, object or array. The last four spell one source other ways; the
    * same hazard in an object that is no concatenation is older than this rule and not claimed here.
    */
  val concatenationHazards = List(
    ("a value and a leaf before the empty object", "app={} {q=0, q.a=1, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("a dotted leaf before two empty objects", "app={} {q.a.c=2, include \"f.conf\", q {}, q {}}\n", "app.q", 1, 1),
    (
      "a dotted leaf before two empty leaf objects",
      "app={} {q.a.c=2, include \"f.conf\", q.a {}, q.a {}}\n",
      "app.q.a",
      1,
      1
    ),
    ("an empty leaf object before the empty object", "app={} {q=0, q.a {}, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("an object value before the empty objects", "app={} {q={z=3}, include \"f.conf\", q {}, q {}}\n", "app.q", 1, 1),
    ("a value and a dotted leaf before it", "app={} {q=0, q.a.c=2, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("two values before it", "app={} {q=0, q={z=3}, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("a null and a leaf before it", "app={} {q=null, q.a=1, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("a null and an empty leaf before it", "app={} {q=null, q.a {}, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("a null and a dotted leaf before it", "app={} {q=null, q.a.c=2, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("a null and an object before it", "app={} {q=null, q={z=3}, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("an array and a leaf before it", "app={} {q=[], q.a=1, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("an array and an empty leaf before it", "app={} {q=[], q.a {}, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("an array and a dotted leaf before it", "app={} {q=[], q.a.c=2, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("an array and an object before it", "app={} {q=[], q={z=3}, include \"f.conf\", q {}}\n", "app.q", 1, 1),
    ("a leaf before two empty objects", "app={} {q.a=1, include \"f.conf\", q {}, q {}}\n", "app.q", 1, 1),
    (
      "a leaf and a dotted leaf before an empty leaf object",
      "app={} {q.a=1, q.a.c=2, include \"f.conf\", q.a {}}\n",
      "app.q.a",
      1,
      1
    ),
    (
      "an empty leaf object before two empty objects",
      "app={} {q.a {}, include \"f.conf\", q {}, q {}}\n",
      "app.q",
      1,
      1
    ),
    ("the same, each statement on its own line", "app={} {\nq=0\nq.a=1\ninclude \"f.conf\"\nq {}\n}\n", "app.q", 5, 4),
    (
      "the same, definitions joined on the include's line",
      "app={} {\nq=0, q.a=1, include \"f.conf\", q {}\n}\n",
      "app.q",
      2,
      2
    ),
    ("the same, CRLF line endings", "app={} {\r\nq=0, q.a=1, include \"f.conf\", q {}\r\n}\r\n", "app.q", 2, 2),
    ("the same, a quoted non-ASCII key", "\"表\"={} {q=0, q.a=1, include \"f.conf\", q {}}\n", "表.q", 1, 1)
  )

  /** The three ordinary object-concatenation shapes the fourth round pinned as must-format are
    * refused again, and the refusal is documented over-refusal: the doubt covers a concatenation's
    * definitions whole, so nothing beside the include inside `app={servers=["one"]} { ... }`
    * vouches for the empty section, although none of the three can change the values — the first
    * `pool {}` is followed by `pool.size=8` at its own path, the last is preceded by
    * `pool {size=8}`, and `metrics {}` is not dropped at all (the rendering keeps it). The oracle
    * ran every one against 29 included bodies, `pool` and `metrics` shapes among them, and resolved
    * the same before and after on the revision that formatted them; refusing them is the price of
    * the rule that keeps the hazards above out. `metrics {}` is also not one to delete by hand: with
    * the include's `metrics = 9` it is what clears the included scalar, so the workaround the
    * refusal suggests — deleting the dropped definition — does not apply to it.
    */
  val objectConcatenationsOverRefused = List(
    (
      "an empty object before its leaf",
      "app={servers=[\"one\"]} {\ninclude \"f.conf\"\npool {}\npool.size=8\n}\n",
      "app.pool",
      3,
      2
    ),
    (
      "an empty section beside another key",
      "app={servers=[\"one\"]} {\ninclude \"f.conf\"\nmetrics {}\npool.size=8\n}\n",
      "app.metrics",
      3,
      2
    ),
    (
      "an empty object after its leaf",
      "app={servers=[\"one\"]} {\ninclude \"f.conf\"\npool {size=8}\npool {}\n}\n",
      "app.pool",
      4,
      2
    )
  )
}
