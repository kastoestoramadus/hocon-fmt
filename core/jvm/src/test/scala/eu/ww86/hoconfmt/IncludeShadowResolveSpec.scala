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
      val input  = beside(root.resolve(s"in-$index"), shadowCase)
      val output = beside(root.resolve(s"out-$index"), shadowCase)
      Verdict.of(Files.readAllBytes(input), "main.conf") match {
        // Leaving the file alone is what the user does by hand, and always allowed.
        case Verdict.Refused(_) => ()
        case verdict            =>
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
      }
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
