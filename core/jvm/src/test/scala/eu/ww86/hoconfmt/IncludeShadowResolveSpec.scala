package eu.ww86.hoconfmt

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import org.ekrich.config.{ConfigException, ConfigFactory, ConfigResolveOptions}

/** The include-shadow family, with the files the includes name on disk.
  *
  * The formatter cannot read those files — a web page has no filesystem and the target may be a
  * URL — so the generated cases put every shape of definition around an include. What the user can
  * see here and the formatter cannot is what each file holds: this resolves the text before and
  * after formatting and holds the formatter to either refusing the case or keeping the values.
  *
  * Needs `ConfigFactory.parseFile` and the file includer behind it, which sconfig's Scala Native
  * build does not implement (`NotImplementedError`), so this runs on the JVM only.
  */
class IncludeShadowResolveSpec extends munit.FunSuite {

  // One test per layout: the family is large, and a test's own timeout should answer for one
  // layout rather than for all of them. A layout that grows past the default can be given its own
  // timeout, and a failure names the layout it stands in.
  HoconGen.Layout.values.foreach { layout =>
    test(s"the $layout layout: every case is refused or keeps resolving the same") {
      checkLayout(layout)
    }
  }

  /** One layout of the family, deduplicated by text and resolved only where the formatting changed
    * something.
    *
    * The verdict depends on the text alone — the included file is unknown at format time, which is
    * what the check protects — so the 36,540 cases hold only 4,060 distinct texts, and checking one
    * of them per body would format the same text nine times. And the comparison needs a file only
    * where the formatting hands back different text: where the verdict says the text is already
    * formatted, the output is the input and the values are equal by construction.
    *
    * The body's file is written once per body and the two texts once per text: the resolution is
    * what costs, and rewriting the same content for every case made the suite slow enough to time
    * out on a runner.
    */
  def checkLayout(layout: HoconGen.Layout): Unit = {
    val root    = Files.createTempDirectory(s"include-shadow-$layout")
    val changed = HoconGen
      .shadowCases(layout)
      .groupBy(_.text)
      .toList
      .sortBy(_._1)
      .map { case (text, _) => text -> Verdict.of(text.getBytes(UTF_8), "main.conf") }
      .collect { case (text, Verdict.NeedsFormatting(formatted)) => (text, formatted) }
    val input  = root.resolve("in.conf")
    val output = root.resolve("out.conf")
    HoconGen.shadowBodies.foreach { body =>
      write(root.resolve("inc.conf"), body + "\n")
      changed.foreach { case (text, formatted) =>
        write(input, text)
        write(output, formatted)
        assertEquals(
          resolved(output),
          resolved(input),
          s"formatting changed what the text resolves to:\n$text"
        )
      }
    }
  }

  test("a definition dropped under a multi-segment path is refused") {
    val root  = Files.createTempDirectory("include-shadow-paths")
    val cases = List(
      "dotted value then object"        -> ("a.o = 3\na.o.c = 7\n", "a.o { retained = 9 }"),
      "nested value then object"        -> ("a { o = 3 }\na.o.c = 7\n", "a.o { retained = 9 }"),
      "three-segment value then object" -> ("a.o.b = 3\na.o.b.c = 7\n", "a.o.b { retained = 9 }"),
      "nested path, dotted leaf"        -> ("a.o { b = 3 }\na.o.b.c = 7\n", "a.o.b { retained = 9 }")
    )
    cases.zipWithIndex.foreach { case ((name, (definitions, body)), index) =>
      val text    = s"${HoconGen.shadowInclude}\n$definitions"
      val verdict = refusedOrUnchanged(root, index, HoconGen.ShadowCase(text, HoconGen.Layout.OwnLines, body))
      verdict match {
        case Verdict.Refused(_) => ()
        case other              => fail(s"$name: expected a refusal, got $other")
      }
    }
  }

  test("a definition dropped on a line another definition shares is refused") {
    val root  = Files.createTempDirectory("include-shadow-one-line")
    val body  = "a.o { retained = 9 }"
    val cases = List(
      "dotted value then object"      -> s"${HoconGen.shadowInclude}\na.o = 3, a.o.c = 7\n",
      "nested value then object"      -> s"${HoconGen.shadowInclude}\na { o = 3 }, a.o.c = 7\n",
      "the same path written twice"   -> s"${HoconGen.shadowInclude}\na.o = 3, a.o = { c = 7 }\n",
      "an empty object after a value" -> s"${HoconGen.shadowInclude}\na.o = 3, a.o {}\n",
      "on the include's own line"     -> s"${HoconGen.shadowInclude}, a.o = 3, a.o.c = 7",
      "inside an object"              -> "a {\n  include \"inc.conf\"\n  o = 3, o.c = 7\n}\n"
    )
    cases.zipWithIndex.foreach { case ((name, text), index) =>
      refusedOrUnchanged(root, index, HoconGen.ShadowCase(text, HoconGen.Layout.OwnLines, body)) match {
        case Verdict.Refused(_) => ()
        case other              => fail(s"$name: expected a refusal, got $other")
      }
    }
  }

  test("the include's place decides: after the definitions it still formats") {
    val root = Files.createTempDirectory("include-shadow-places")
    // The dropped dotted definition is the include's own line, so the shadow check has nothing to
    // refuse; the moved-include check refuses this one on its own, which keeping the values allows.
    val between = "a.o = 3\ninclude \"inc.conf\"\na.o.c = 7\n"
    val _       = refusedOrUnchanged(root, 0, HoconGen.ShadowCase(between, HoconGen.Layout.OwnLines, "a.o { retained = 9 }"))
    val after   = "a.o = 3\na.o.c = 7\ninclude \"inc.conf\"\n"
    refusedOrUnchanged(root, 1, HoconGen.ShadowCase(after, HoconGen.Layout.OwnLines, "a.o { retained = 9 }")) match {
      case Verdict.Refused(refusal) => fail(s"the include after the definitions: refused: ${refusal.reason}")
      case _                        => ()
    }
  }

  /** The case's text beside the file its include names, formatted and resolved against the input:
    * a refusal passes, any other verdict must keep the values.
    */
  def refusedOrUnchanged(root: Path, index: Int, shadowCase: HoconGen.ShadowCase): Verdict = {
    val input  = beside(root.resolve(s"in-$index"), shadowCase)
    val output = beside(root.resolve(s"out-$index"), shadowCase)
    Verdict.of(Files.readAllBytes(input), "main.conf") match {
      case verdict @ Verdict.Refused(_) => verdict
      case verdict                      =>
        val formatted = verdict match {
          case Verdict.NeedsFormatting(text) => text
          case _                             => shadowCase.text
        }
        write(output, formatted)
        assertEquals(
          resolved(output),
          resolved(input),
          s"formatting changed what the text resolves to:\n${shadowCase.text}"
        )
        verdict
    }
  }

  /** `main.conf` holding the case's text, with the file its include names beside it. */
  def beside(dir: Path, shadowCase: HoconGen.ShadowCase): Path = {
    val _    = Files.createDirectories(dir)
    val main = dir.resolve("main.conf")
    write(dir.resolve("inc.conf"), shadowCase.includeBody + "\n")
    write(main, shadowCase.text)
    main
  }

  def write(file: Path, text: String): Unit = {
    val _ = Files.write(file, text.getBytes(UTF_8))
  }

  /** The file's values, or none when it does not resolve at all. */
  def resolved(file: Path): Option[Any] =
    try Some(ConfigFactory.parseFile(file.toFile).resolve(ConfigResolveOptions.noSystem).root.unwrapped)
    catch { case _: ConfigException => None }
}
