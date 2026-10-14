package eu.ww86.hoconfmt

import eu.ww86.hoconfmt.HoconFormatter.format

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

  // The one option that does not reach the renderer: it says what the command line does with the
  // duplicate report, and the config file names it like the rest.
  test("fail-on-duplicates parses, defaults to false, and refuses anything else") {
    assertEquals(FormatOptions.default.failOnDuplicates, false)
    assertEquals(
      FormatOptions.parse("fail-on-duplicates = true", "team.conf"),
      Right(FormatOptions(failOnDuplicates = true))
    )
    assertEquals(
      FormatOptions.parse("fail-on-duplicates = maybe", "team.conf"),
      Left("team.conf: fail-on-duplicates: expected true or false, got: maybe")
    )
  }

  test("fail-on-refusal is opt-in and validates boolean configuration") {
    val enabled = FormatOptions.parse("fail-on-refusal = true", "team.conf")
    assert(enabled.isRight, enabled)
    assert(enabled != Right(FormatOptions.default))
    assertEquals(FormatOptions.parse("fail-on-refusal = false", "team.conf"), Right(FormatOptions.default))
    assertEquals(
      FormatOptions.parse("fail-on-refusal = maybe", "team.conf"),
      Left("team.conf: fail-on-refusal: expected true or false, got: maybe")
    )
    assertEquals(
      FormatOptions.parse("fail-on-refusal = null", "team.conf"),
      Left("team.conf: fail-on-refusal: expected true or false, got: ?")
    )
  }

  test("an explicit null is an error naming the file and the key, not an absent option") {
    List("separator", "double-indent", "simplify-nested-objects", "fail-on-duplicates").foreach { key =>
      val result = FormatOptions.parse(s"$key = null", "team.conf")
      assert(result.left.exists(m => m.startsWith("team.conf: ") && m.contains(key)), s"$key = null gave $result")
    }
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

  test("a bad value is an error naming the file, the key and the value") {
    assertEquals(
      FormatOptions.parse("separator = equals", "team.conf"),
      Left("team.conf: separator: expected \"=\" or \":\", got: equals")
    )
    assertEquals(
      FormatOptions.parse("separator = [1]", "team.conf"),
      Left("team.conf: separator: expected \"=\" or \":\", got: [1]")
    )
    assertEquals(
      FormatOptions.parse("double-indent = maybe", "team.conf"),
      Left("team.conf: double-indent: expected true or false, got: maybe")
    )
    assertEquals(
      FormatOptions.parse("""separator = "a\nb"""", "team.conf"),
      Left("""team.conf: separator: expected "=" or ":", got: a\nb""")
    )
  }

  test("style nesting just below and at the limit still validates option names") {
    List(79, 80).foreach { depth =>
      val text = "style {" * depth + "x=1" + "}" * depth
      assertEquals(FormatOptions.parse(text, "team.conf"), Left("team.conf: unknown option: style"))
    }
  }

  test("style nesting over the limit returns a named option error before parsing") {
    List(81, 10000).foreach { depth =>
      val text = "style {" * depth + "x=1" + "}" * depth
      assertEquals(
        FormatOptions.parse(text, "team.conf"),
        Left("team.conf: input nesting exceeds the maximum depth of 80")
      )
    }
  }

  List("separator", "fail-on-refusal").foreach { key =>
    List(999, 2999, 9999).foreach { segments =>
      test(s"a style dotted path under $key with $segments segments returns a named single-line error") {
        val text   = key + ".a" * segments + " = 1"
        val result = FormatOptions.parse(text, "team.conf")
        assert(
          result.left.exists(message =>
            message.startsWith("team.conf: ") && !message.contains('\n') && !message.contains('\r')
          )
        )
      }
    }
  }

  test("a config file that is not HOCON is an error naming the file") {
    assert(
      FormatOptions.parse("[[", "team.conf").fold(message => message.startsWith("team.conf:"), _ => false),
      FormatOptions.parse("[[", "team.conf")
    )
  }
}
