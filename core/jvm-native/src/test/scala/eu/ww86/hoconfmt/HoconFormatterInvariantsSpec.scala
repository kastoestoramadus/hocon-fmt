package eu.ww86.hoconfmt

import java.io.File

import eu.ww86.hoconfmt.HoconFormatter.*

/** Invariants that must hold for every input, as a safety net under changes to the
  * include-placeholder preprocessing.
  */
class HoconFormatterInvariantsSpec extends munit.FunSuite with HoconTestSupport {

  // Shared with GoldenFileSpec so golden files are never mistaken for inputs.
  val resourceFiles: List[File] = FileFixtures.inputs

  test("test resources are discovered") {
    assert(resourceFiles.nonEmpty, "no .conf fixtures found")
  }

  // Formatting is a fixed point: running it twice must not differ from running it once.
  // This is what catches the placeholder round-trip breaking on already-formatted input.
  resourceFiles.foreach { file =>
    test(s"idempotent: ${file.getName}") {
      val once  = formatted(FileFixtures.read(file))
      val twice = formatted(once)
      assertEquals(twice, once, s"second pass changed the output of ${file.getName}")
    }
  }

  // The formatter's output must itself be valid HOCON. This used to be an unlabelled
  // side effect inside the example-based tests, where its result was discarded.
  resourceFiles.foreach { file =>
    test(s"output re-parses: ${file.getName}") {
      val once = formatted(FileFixtures.read(file))
      assert(format(once).isRight, s"formatted output of ${file.getName} does not re-parse")
    }
  }

  // Include-free configs can be parsed directly, so meaning can be compared before
  // and after formatting without going through the formatter's own preprocessing.
  def semanticCases = Map(
    "nested objects"   -> """a { b { c : 1 }, d : "x" }""",
    "list and numbers" -> """xs : [1, 2, 3]
                            |pi : 3.14
                            |flag : true""".stripMargin,
    "comments and quoted keys" -> """# leading
                                    |"quoted.key" : "value" // trailing
                                    |plain : 42""".stripMargin,
    "multi-line string" -> "s : \"\"\"\nline1\nline2\n\"\"\"",
    "substitution"      -> """base : 1
                        |derived : ${base}""".stripMargin
  )

  semanticCases.foreach { case (name, raw) =>
    test(s"meaning preserved: $name") {
      assertSameMeaning(formatted(raw), raw, s"formatting changed the meaning of: $name")
    }
  }

  // Adversarial: the retired masking pass injected `__INCLUDE_<n>` and `__INCLUDE_GUARD_<n>`
  // fields into the source; content that looks like one must still survive as the user's own.
  def markerCases = Map(
    "placeholder as a value"      -> """key : "__INCLUDE_0"""",
    "placeholder as a key"        -> """"__INCLUDE_0" : 1""",
    "full placeholder field"      -> """__INCLUDE_0 : "__INCLUDE_0"""",
    "guard field"                 -> """__INCLUDE_GUARD_0 : "g"""",
    "guard field, unquoted value" -> """__INCLUDE_GUARD_0 : g""",
    "inside a multi-line string"  -> "s : \"\"\"\n__INCLUDE_0\n\"\"\""
  )

  markerCases.foreach { case (name, raw) =>
    test(s"marker-like input is not mistaken for a marker: $name") {
      assertSameMeaning(formatted(raw), raw, s"marker-like content was corrupted: $name")
    }
  }

  // The retired masking pass could not tell its own placeholders from lookalike text beside an
  // include, and refused the file rather than restore on a guess; the document engine writes no
  // placeholders, so the same texts format and every spelling survives as written.
  List(
    "value index extends a handed-out one" -> "include \"a.conf\"\nx { __INCLUDE_0 : \"__INCLUDE_01\", y : 1 }",
    "a lookalike in a comment"             -> "# note __INCLUDE_5 :\ninclude \"a.conf\"\nb : 1",
    "guard text in a string"               -> "include \"a.conf\"\na = \"__INCLUDE_GUARD_0 = g\""
  ).foreach { case (name, raw) =>
    test(s"marker-like input next to an include is kept as written: $name") {
      val out = formatted(raw)
      // Formatting where the masking pass refused runs on every platform; the meaning
      // comparison resolves the include, which sconfig cannot on Scala Native: the javalib
      // java.net.URI stubs behind Parseable's relative-include resolution throw
      // NotImplementedError (ParseableURL.relativeTo -> PlatformUri.toURL). Same platform
      // rule as the shared suites' never-parse-includes note for Scala.js.
      assume(
        System.getProperty("java.vm.name", "") != "Scala Native",
        "sconfig on Scala Native cannot resolve a relative include"
      )
      assertSameMeaning(out, raw, s"marker-like content was corrupted: $name")
    }
  }

  test("marker-like input in a file without an include is left alone") {
    assertEquals(formatted("a : \"__INCLUDE_GUARD_0 : g\""), "a = \"__INCLUDE_GUARD_0 : g\"\n")
  }
}
