package ww86.hocon_fmt

import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

/** What formatting makes of a file's content — the decision every integration acts on, so the
  * CLI and the build-tool plugins cannot disagree about a file.
  */
class VerdictSpec extends munit.FunSuite with HoconTestSupport {

  test("formatted content needs nothing") {
    assertEquals(Verdict.of("a = 1\n"), Verdict.AlreadyFormatted)
  }

  test("unformatted content comes with the text it should have") {
    assertEquals(Verdict.of("a   :    1"), Verdict.NeedsFormatting("a = 1\n"))
    // The separator is an option: a `:`-written file is formatted only when it is asked for.
    assertEquals(Verdict.of("a: 1\n", FormatOptions(separator = Separator.Colon)), Verdict.AlreadyFormatted)
  }

  test("content the formatter refuses carries the reason") {
    Verdict.of("a : ${") match {
      case Verdict.Refused(Refusal.NotHocon(_)) => ()
      case other                                => fail(s"expected NotHocon, got $other")
    }
  }

  // The origin is what a parse failure names as the place it tripped: "file.conf: 8: ...".
  test("a parse failure carries the origin it was given") {
    HoconFormatter.format("a : ${", "conf/application.conf") match {
      case Left(Refusal.NotHocon(detail)) => assert(detail.startsWith("conf/application.conf:"), detail)
      case other                          => fail(s"expected NotHocon, got $other")
    }
  }

  test("an origin that parses comes back formatted") {
    assertEquals(HoconFormatter.format("a   :   1", "conf/application.conf"), Right("a = 1\n"))
  }

  // Pinning the no-origin signature: parseString has always reported itself as "String".
  test("without an origin, a parse failure says String, as it always has") {
    HoconFormatter.format("a : ${") match {
      case Left(Refusal.NotHocon(detail)) => assert(detail.startsWith("String:"), detail)
      case other                          => fail(s"expected NotHocon, got $other")
    }
  }

  test("a named file's parse failure carries the file's name") {
    Verdict.of("a : ${".getBytes(UTF_8), "conf/application.conf") match {
      case Verdict.Refused(Refusal.NotHocon(detail)) => assert(detail.startsWith("conf/application.conf:"), detail)
      case other                                     => fail(s"expected NotHocon, got $other")
    }
  }

  // Lightbend's loader reads .conf, .json and .properties alike, but the other two are formats of
  // their own: a .json file comes back from a round trip as HOCON with its objects reordered, and
  // a .properties file's values break HOCON's value tokens. Formatting them is not this
  // formatter's job, so the name alone decides.
  test("a .json file is refused as JSON, not rewritten as HOCON") {
    Verdict.of("""{"b": 1, "a": 2}""".getBytes(UTF_8), "application.json") match {
      case Verdict.Refused(Refusal.OtherFormat(format)) =>
        assertEquals(format, "JSON")
      case Verdict.NeedsFormatting(formatted) => fail(s"application.json was rewritten as HOCON: $formatted")
      case other                              => fail(s"expected OtherFormat, got $other")
    }
  }

  test("a .properties file is refused as Java properties") {
    Verdict.of("server.port=8080\n".getBytes(UTF_8), "application.properties") match {
      case Verdict.Refused(Refusal.OtherFormat(format)) =>
        assertEquals(format, "Java properties")
      case other => fail(s"expected OtherFormat, got $other")
    }
  }

  test("the extension decides, however it is capitalised") {
    Verdict.of("{}".getBytes(UTF_8), "APP.JSON") match {
      case Verdict.Refused(Refusal.OtherFormat(format)) => assertEquals(format, "JSON")
      case other                                        => fail(s"expected OtherFormat, got $other")
    }
  }

  test("a name that promises HOCON is no refusal, whatever the text turns out to be") {
    assertEquals(Verdict.of("a   :   1".getBytes(UTF_8), "app.conf"), Verdict.NeedsFormatting("a = 1\n"))
    assertEquals(Verdict.of("a = 1\n".getBytes(UTF_8), "<stdin>"), Verdict.AlreadyFormatted)
    assertEquals(Verdict.of("a   :   1", "app.conf"), Verdict.NeedsFormatting("a = 1\n"))
  }

  test("bytes are decoded as UTF-8, so non-ASCII text survives") {
    Verdict.of("a   :   \"zażółć\"".getBytes(UTF_8)) match {
      case Verdict.NeedsFormatting(formatted) => assert(formatted.contains("zażółć"), formatted)
      case other                              => fail(s"expected NeedsFormatting, got $other")
    }
  }

  // A lenient decode turns each invalid byte into U+FFFD, and writing that back destroys it.
  test("bytes that are not UTF-8 are refused rather than decoded leniently") {
    val latin2 = "a : \"³\"".getBytes(ISO_8859_1) // ł in ISO-8859-2
    assertEquals(Verdict.of(latin2), Verdict.Refused(Refusal.NotUtf8))
  }

  test("every refusal explains itself") {
    val refusals = List(
      Refusal.NotUtf8,
      Refusal.NotHocon("detail"),
      Refusal.OtherFormat("JSON"),
      Refusal.BrokenOutput("detail"),
      Refusal.LostComment("detail"),
      Refusal.LostInclude("detail"),
      Refusal.MovedInclude("detail"),
      Refusal.ReservedName,
      Refusal.UnstableOutput
    )
    refusals.foreach(r => assert(r.reason.nonEmpty, s"$r has no reason"))
  }
}
