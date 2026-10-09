package eu.ww86.hoconfmt

import eu.ww86.hoconfmt.KeyPath.Segment

/** The report of keys a later definition replaces.
  *
  * Each case records what HOCON resolves the text to, so the expectation is not a restatement of
  * the code: the earlier definition really is the one that never takes effect.
  */
class DuplicateReportSpec extends munit.FunSuite with HoconTestSupport {

  def report(hocon: String): List[Finding] =
    DuplicateReport.findings(hocon).fold(refusal => fail(s"refused: ${refusal.reason}"), identity)

  def only(hocon: String): Finding = report(hocon) match {
    case found :: Nil => found
    case other        => fail(s"expected exactly one finding, got: $other")
  }

  def pathOf(hocon: String): KeyPath = only(hocon) match {
    case Finding.KeyDefinedAgain(keyPath, _, _) => keyPath
  }

  def dead(source: String, resolution: String)(using munit.Location): Unit = {
    only(source) match {
      case Finding.KeyDefinedAgain(_, earlier, later) =>
        assert(later > earlier, s"the later definition should come after: $only")
    }
    assertSameMeaning(formatted(source), resolution, s"the earlier definition was not the dead one:\n$source")
  }

  test("a scalar replaced by a scalar") {
    val source = "x = 1\nx = 2\n"
    dead(source, "x = 2\n")
    assertEquals(only(source), Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 2))
  }

  test("an array replaced by an array") {
    val source = "x = [ 1 ]\nx = [ 2, 3 ]\n"
    dead(source, "x = [ 2, 3 ]\n")
    assertEquals(only(source), Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 2))
  }

  test("an object replaced by a value") {
    val source = "x { a = 1 }\nx = 2\n"
    dead(source, "x = 2\n")
    assertEquals(only(source), Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 2))
  }

  test("a value replaced by an object") {
    val source = "x = 1\nx { a = 2 }\n"
    dead(source, "x.a = 2\n")
    assertEquals(only(source), Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 2))
  }

  // The dotted path writes an object, and a substitution falling back to it is not replaced: what
  // the substitution resolves to merges into that object, so the earlier value still contributes.
  test("an object written over a substitution merges with what it resolves to") {
    val source = "consumer { a = 1 }\nadmin = ${consumer}\nadmin.timeout = 5s\n"
    assertEquals(report(source), Nil)
    // The formatter refuses this shape (an unresolved object merge does not render again), so the
    // semantics are asserted by resolution: what the substitution names is beside the new field.
    assertEquals(
      source.parsedConfig.resolve(),
      "consumer { a = 1 }\nadmin { a = 1, timeout = 5s }\n".parsedConfig.resolve(),
      "the substitution's fields did not survive the merge"
    )
    assertEquals(report("x = { a = 1 }\ny = ${x}\ny.b = 2\n"), Nil)
  }

  test("an object written over a plain value replaces it") {
    assertEquals(report("x = 1\nx.b = 2\n"), List(Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 2)))
    assertEquals(report("x = 1\nx { b = 2 }\n"), List(Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 2)))
  }

  test("objects merge, so neither is reported") {
    assertEquals(report("x { a = 1 }\nx { b = 2 }\n"), Nil)
    assertSameMeaning(formatted("x { a = 1 }\nx { b = 2 }\n"), "x { a = 1, b = 2 }\n", "a merge lost a field")
  }

  test("a leaf inside a merged object is reported") {
    val source = "x { a = 1, b = 2 }\nx { a = 3 }\n"
    dead(source, "x { a = 3, b = 2 }\n")
    assertEquals(only(source), Finding.KeyDefinedAgain(KeyPath.of("x", "a"), 1, 2))
  }

  test("a dotted path replaces a leaf written inside the block") {
    val source = "a { b = 1 }\na.b = 2\n"
    dead(source, "a.b = 2\n")
    assertEquals(only(source), Finding.KeyDefinedAgain(KeyPath.of("a", "b"), 1, 2))
  }

  test("a dotted path replaces a leaf written as a dotted path") {
    val source = "a.b = 1\na.b = 2\n"
    dead(source, "a.b = 2\n")
    assertEquals(only(source), Finding.KeyDefinedAgain(KeyPath.of("a", "b"), 1, 2))
  }

  // a.b = 2 makes the object a; the scalar written at line 1 is everything that object replaced.
  test("a dotted path replaces the value that held nothing else") {
    val source = "a = 1\na.b = 2\n"
    dead(source, "a { b = 2 }\n")
    assertEquals(only(source), Finding.KeyDefinedAgain(KeyPath.of("a"), 1, 2))
  }

  test("the deepest leaf of a nested block is reported, however far the second definition stands") {
    val source = "top { inner { x = 1 } }\ntop.inner.x = 2\n"
    dead(source, "top.inner.x = 2\n")
    assertEquals(only(source), Finding.KeyDefinedAgain(KeyPath.of("top", "inner", "x"), 1, 2))
  }

  // The formatter refuses this shape (an unresolved merge at the root does not render again), so
  // only the report is asserted: the earlier value is what an unset ENV leaves standing.
  test("the env-override idiom is not reported") {
    assertEquals(report("x = \"default\"\nx = ${?ENV}\n"), Nil)
  }

  test("the idiom is not reported inside an object, on a dotted path") {
    assertEquals(report("a { x = \"default\" }\na.x = ${?ENV}\n"), Nil)
    assertEquals(report("a.x = \"default\"\na.x = ${?ENV}\n"), Nil)
  }

  test("a later definition that refers to the earlier value is not reported") {
    // Each resolves to the earlier value: the substitution is resolved against what it replaced.
    List("x = 1\nx = ${x}\n", "x = 1\nx = ${?x}\n", "x = [1]\nx += 2\n", "x { a = 1 }\nx = ${x.a}\n").foreach {
      source =>
        assertEquals(report(source), Nil, source)
    }
  }

  // A substitution leaves the merge unresolved: the earlier value is what the merge falls back to,
  // so no later definition that holds one is reported.
  test("a later definition holding a substitution is not reported") {
    assertEquals(report("x = 1\nx = ${y}\ny = 2\n"), Nil)
    assertEquals(report("x = 1\nx = ${y}\"s\"\ny = 2\n"), Nil)
  }

  // An optional override only works as the last definition of its key: a plain value after it, or
  // another definition of the object it stands in, leaves it no room. Every case resolves to the
  // later value, so the definition before it is dead.
  test("what a later definition shadows is reported, and the resolution agrees") {
    val overridden = "u { url = ${?A}\n url = \"literal\"\n url = ${?B} }\nA = \"from-a\"\n"
    assertEquals(report(overridden), List(Finding.KeyDefinedAgain(KeyPath.of("u", "url"), 1, 2)))
    assertEquals(
      overridden.parsedConfig.resolve(),
      "u.url = \"literal\"\nA = \"from-a\"\n".parsedConfig.resolve(),
      "the optional override was not shadowed"
    )

    val appended = "f { enabled += \"x\" }\nf.enabled = []\n"
    assertEquals(report(appended), List(Finding.KeyDefinedAgain(KeyPath.of("f", "enabled"), 1, 2)))
    assertEquals(appended.parsedConfig.resolve(), "f.enabled = []\n".parsedConfig.resolve(), "+= survived")
  }

  test("what only looks like a definition is not one") {
    assertEquals(report("# x = 1\nx = 2\n"), Nil)
    assertEquals(report("x = \"a\"\n// x = \"b\"\n"), Nil)
    assertEquals(report("x = \"1 = 1\"\n"), Nil)
  }

  test("a quoted key is one segment, so it is not the path a dotted key writes") {
    val quoted = "\"a.b\" = 1\n\"a.b\" = 2\n"
    dead(quoted, "\"a.b\" = 2\n")
    assertEquals(only(quoted), Finding.KeyDefinedAgain(KeyPath.of("a.b"), 1, 2))
    assertEquals(pathOf(quoted).rendered, "\"a.b\"")
    assertEquals(report("\"a.b\" = 1\na.b = 2\n"), Nil)
  }

  test("the objects in an array are reported by their position") {
    val source = "a = [ { b = 1, b = 2 } ]\n"
    assertEquals(
      report(source),
      List(Finding.KeyDefinedAgain(KeyPath(List(Segment.Name("a"), Segment.Index(0), Segment.Name("b"))), 1, 1))
    )
    assertEquals(pathOf(source).rendered, "a[0].b")
  }

  test("array values have independent element scopes, including concatenation and self-reference") {
    List(
      "a = [{b=1}] [{b=2}]",
      "a=[{b=1}]\na=${a} [{b=2}]"
    ).foreach { source =>
      assertEquals(source.parsedConfig.resolve(), "a=[{b=1},{b=2}]".parsedConfig.resolve())
      assertEquals(report(source), Nil, source)
    }
    assertEquals(
      report("a=[{b=1}]\na=[{b=2}]"),
      List(Finding.KeyDefinedAgain(KeyPath.of("a"), 1, 2))
    )
    val local = "a=[{b=1,b=2}] [{b=3,b=4}]"
    val path  = KeyPath(List(Segment.Name("a"), Segment.Index(0), Segment.Name("b")))
    assertEquals(report(local), List.fill(2)(Finding.KeyDefinedAgain(path, 1, 1)))
  }

  test("an ancestor replacement cuts descendants before a fresh object defines them again") {
    val source = "a {b=1}\na=0\na {b=2}"
    assertEquals(source.parsedConfig.resolve(), "a {b=2}".parsedConfig.resolve())
    assertEquals(
      report(source),
      List(
        Finding.KeyDefinedAgain(KeyPath.of("a"), 1, 2),
        Finding.KeyDefinedAgain(KeyPath.of("a"), 2, 3)
      )
    )
    assertEquals(
      report("a {\n b=1\n}\na=0\na {b=2}"),
      List(
        Finding.KeyDefinedAgain(KeyPath.of("a"), 1, 4),
        Finding.KeyDefinedAgain(KeyPath.of("a", "b"), 2, 4),
        Finding.KeyDefinedAgain(KeyPath.of("a"), 4, 5)
      )
    )
  }

  test("every earlier definition of a path is reported, against the one that replaces it") {
    assertEquals(
      report("x = 1\nx = 2\nx = 3\n"),
      List(
        Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 2),
        Finding.KeyDefinedAgain(KeyPath.of("x"), 2, 3)
      )
    )
  }

  // A later optional definition may not take effect at all, so nothing before it is dead by it;
  // a later plain one leaves no option alive and kills everything before it.
  test("each definition a later plain value kills is reported, and no other") {
    assertEquals(
      report("x = 1\nx = ${?E}\nx = 2\n"),
      List(
        Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 3),
        Finding.KeyDefinedAgain(KeyPath.of("x"), 2, 3)
      )
    )
    assertEquals(report("x = 1\nx = 2\nx = ${?E}\n"), List(Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 2)))
  }

  test("lines count from the start of the field, past values that span lines") {
    val source = "x = [\n  1\n]\nx = 2\n"
    assertEquals(only(source), Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 4))
    assertEquals(report("# a comment\n# another\nx = 1\nx = 2\n"), List(Finding.KeyDefinedAgain(KeyPath.of("x"), 3, 4)))
  }

  // An include is a statement of its own: nothing it names is read, and the lines after it are the
  // source's, not the ones a masked text would have.
  test("an include changes nothing about the report around it") {
    assertEquals(
      report("include \"missing.conf\"\nx = 1\nx = 2\n"),
      List(Finding.KeyDefinedAgain(KeyPath.of("x"), 2, 3))
    )
    assertEquals(
      report("x = 1\nx = 2\ninclude required(file(\"missing.conf\"))\n"),
      List(Finding.KeyDefinedAgain(KeyPath.of("x"), 1, 2))
    )
    assertEquals(
      report("include \"a.conf\"\nx = 1\ninclude \"b.conf\"\nx = 2\n"),
      List(Finding.KeyDefinedAgain(KeyPath.of("x"), 2, 4))
    )
  }

  test("text that does not parse is a refusal, not an exception") {
    assert(DuplicateReport.findings("a : ${").isLeft)
    assert(DuplicateReport.findings("[[[").isLeft)
  }

  test("text with nothing repeated has nothing to report") {
    assertEquals(report(""), Nil)
    assertEquals(report("x = 1\n"), Nil)
    assertEquals(report("a { b = 1 }\nc.d = 2\n"), Nil)
    assertEquals(report("a = [ { b = 1 }, { b = 2 } ]\n"), Nil)
  }
}
