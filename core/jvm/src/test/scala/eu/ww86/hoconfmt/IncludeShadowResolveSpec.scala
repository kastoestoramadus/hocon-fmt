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

  test("every include-between-definitions case is refused or keeps resolving the same") {
    val root = Files.createTempDirectory("include-shadow")
    HoconGen.shadowCases.zipWithIndex.foreach { case (shadowCase, index) =>
      refusedOrUnchanged(root, index, shadowCase)
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
      val verdict = refusedOrUnchanged(root, index, HoconGen.ShadowCase(text, body))
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
      refusedOrUnchanged(root, index, HoconGen.ShadowCase(text, body)) match {
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
    val _       = refusedOrUnchanged(root, 0, HoconGen.ShadowCase(between, "a.o { retained = 9 }"))
    val after   = "a.o = 3\na.o.c = 7\ninclude \"inc.conf\"\n"
    refusedOrUnchanged(root, 1, HoconGen.ShadowCase(after, "a.o { retained = 9 }")) match {
      case Verdict.Refused(refusal) => fail(s"the include after the definitions: refused: ${refusal.reason}")
      case _                        => ()
    }
  }

  /** The case's text beside the file its include names, formatted and resolved against the input:
    * a refusal passes, any other verdict must keep the values.
    */
  private def refusedOrUnchanged(root: Path, index: Int, shadowCase: HoconGen.ShadowCase): Verdict = {
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
  private def beside(dir: Path, shadowCase: HoconGen.ShadowCase): Path = {
    val _    = Files.createDirectories(dir)
    val main = dir.resolve("main.conf")
    write(dir.resolve("inc.conf"), shadowCase.includeBody + "\n")
    write(main, shadowCase.text)
    main
  }

  private def write(file: Path, text: String): Unit = {
    val _ = Files.write(file, text.getBytes(UTF_8))
  }

  /** The file's values, or none when it does not resolve at all. */
  private def resolved(file: Path): Option[Any] =
    try Some(ConfigFactory.parseFile(file.toFile).resolve(ConfigResolveOptions.noSystem).root.unwrapped)
    catch { case _: ConfigException => None }
}
