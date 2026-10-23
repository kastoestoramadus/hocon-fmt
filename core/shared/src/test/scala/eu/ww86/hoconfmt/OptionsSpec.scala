package eu.ww86.hoconfmt

/** The parse and render options the formatter pins explicitly.
  *
  * sconfig's option objects have moved before — sconfig#566 removed `FormattingOptions` from under
  * its users — and a default that changes upstream would change the output without a line here
  * moving. Anything the output depends on is set by [[HoconFormatter]] rather than inherited, and
  * asserted here rather than trusted.
  */
class OptionsSpec extends munit.FunSuite {

  // The final newline is structural in the document engine, so it is pinned by behaviour.
  test("the final newline is decided here, not by an upstream default") {
    assertEquals(HoconFormatter.format("a: 1"), Right("a = 1\n"))
    assertEquals(HoconFormatter.format("a: 1\n"), Right("a = 1\n"))
  }

  // The default separator is `=`, asked for by not setting colonAssign; a `:`-formatted file is
  // requested with FormatOptions(separator = Separator.Colon). Upstream deprecated reading the
  // format options back off the render path, so the pin is behavioural now.
  test("the separator is decided here, not by an upstream default") {
    assertEquals(HoconFormatter.format("a:1"), Right("a = 1\n"))
    assertEquals(
      HoconFormatter.format("a:1", FormatOptions(separator = Separator.Colon)),
      Right("a: 1\n")
    )
  }

  // An empty root would render as "{}"; an empty file stays empty.
  test("empty text stays empty") {
    assertEquals(HoconFormatter.format(""), Right(""))
  }

  test("how env-variable values render is decided here, not by an upstream default") {
    assertEquals(HoconFormatter.renderOptions.getShowEnvVariableValues, true)
  }
}
