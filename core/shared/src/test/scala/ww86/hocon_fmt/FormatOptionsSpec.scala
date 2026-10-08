package ww86.hocon_fmt

import ww86.hocon_fmt.HoconFormatter.format

/** The style options: what they default to, how the renderer is asked for them, and how a
  * `.hocon-fmt.conf` names them. Parsing the option values lives in the core, pure, so the
  * build-tool plugins can reuse it when they grow config-file lookup of their own.
  */
class FormatOptionsSpec extends munit.FunSuite {

  private val colon = FormatOptions(separator = Separator.Colon)

  test("the default separator is =") {
    assertEquals(
      FormatOptions.default,
      FormatOptions(Separator.Equals, doubleIndent = false, simplifyNestedObjects = true)
    )
    assertEquals(format("a : 1"), Right("a = 1\n"))
    assertEquals(format("a = 1"), Right("a = 1\n"))
  }

  test("the separator is : on request") {
    assertEquals(format("a : 1", colon), Right("a: 1\n"))
    assertEquals(format("a = 1", colon), Right("a: 1\n"))
  }

  test("double-indent asks for the wider indent") {
    val text = "a { b : 1 }"
    assertEquals(
      format(text, FormatOptions(doubleIndent = true, simplifyNestedObjects = false)),
      Right("a {\n    b = 1\n}\n")
    )
  }

  test("simplify-nested-objects = false keeps the braces") {
    assertEquals(format("a { b : 1 }"), Right("a.b = 1\n"))
    assertEquals(format("a { b : 1 }", FormatOptions(simplifyNestedObjects = false)), Right("a {\n  b = 1\n}\n"))
  }

  test("a verdict follows the options it was given") {
    assertEquals(Verdict.of("a = 1\n"), Verdict.AlreadyFormatted)
    assertEquals(Verdict.of("a = 1\n", colon), Verdict.NeedsFormatting("a: 1\n"))
    assertEquals(Verdict.of("a : 1", "app.conf", colon), Verdict.NeedsFormatting("a: 1\n"))
  }

  test("the config file names the options in HOCON style") {
    assertEquals(FormatOptions.parse("separator = \":\"\n", ".hocon-fmt.conf"), Right(colon))
    assertEquals(FormatOptions.parse("separator: \"=\"", ".hocon-fmt.conf"), Right(FormatOptions.default))
    assertEquals(FormatOptions.parse("double-indent = true", "team.conf"), Right(FormatOptions(doubleIndent = true)))
    assertEquals(
      FormatOptions.parse("simplify-nested-objects = false", "team.conf"),
      Right(FormatOptions(simplifyNestedObjects = false))
    )
    assertEquals(FormatOptions.parse("", "team.conf"), Right(FormatOptions.default))
    assertEquals(FormatOptions.parse("# only a comment\n", "team.conf"), Right(FormatOptions.default))
  }

  test("an unknown key is an error naming the file and the key, never silently ignored") {
    assertEquals(
      FormatOptions.parse("separators = \":\"", ".hocon-fmt.conf"),
      Left(".hocon-fmt.conf: unknown option: separators")
    )
    assertEquals(
      FormatOptions.parse("style { indent = 4 }", ".hocon-fmt.conf"),
      Left(".hocon-fmt.conf: unknown option: style")
    )
  }

  test("a bad value is an error naming the file and the key") {
    assertEquals(
      FormatOptions.parse("separator = equals", "team.conf"),
      Left("team.conf: separator: expected \"=\" or \":\", got: equals")
    )
    assertEquals(
      FormatOptions.parse("separator = [1]", "team.conf"),
      Left("team.conf: separator: expected \"=\" or \":\"")
    )
    assertEquals(
      FormatOptions.parse("double-indent = maybe", "team.conf"),
      Left("team.conf: double-indent: expected true or false")
    )
  }

  test("a config file that is not HOCON is an error naming the file") {
    assert(
      FormatOptions.parse("[[", "team.conf").fold(message => message.startsWith("team.conf:"), _ => false),
      FormatOptions.parse("[[", "team.conf")
    )
  }
}
