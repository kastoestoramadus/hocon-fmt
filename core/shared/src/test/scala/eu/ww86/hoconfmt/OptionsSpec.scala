package eu.ww86.hoconfmt

/** The parse and render options the formatter pins explicitly.
  *
  * sconfig's option objects have moved before — sconfig#566 removed `FormattingOptions` from under
  * its users — and a default that changes upstream would change the output without a line here
  * moving. Anything the output depends on is set by [[HoconFormatter]] rather than inherited, and
  * asserted here rather than trusted.
  */
class OptionsSpec extends munit.FunSuite {

  test("the final newline is decided here, not by an upstream default") {
    assertEquals(HoconFormatter.renderOptions.getConfigFormatOptions.getNewLineAtEnd, true)
  }

  // The default separator is `=`, so the renderer is asked for it by not setting colonAssign;
  // a `:`-formatted file is requested with FormatOptions(separator = Separator.Colon).
  test("the separator is decided here, not by an upstream default") {
    assertEquals(HoconFormatter.renderOptions.getConfigFormatOptions.getColonAssign, false)
    assertEquals(
      HoconFormatter.renderOptions(FormatOptions(separator = Separator.Colon)).getConfigFormatOptions.getColonAssign,
      true
    )
  }

  test("a rendered document ends with a newline, however the input ended") {
    assertEquals(HoconFormatter.format("a: 1"), Right("a = 1\n"))
    assertEquals(HoconFormatter.format("a: 1\n"), Right("a = 1\n"))
  }

  // An empty root would render as "{}"; an empty file stays empty.
  test("empty text stays empty") {
    assertEquals(HoconFormatter.format(""), Right(""))
  }

  test("how env-variable values render is decided here, not by an upstream default") {
    assertEquals(HoconFormatter.renderOptions.getShowEnvVariableValues, true)
  }
}
