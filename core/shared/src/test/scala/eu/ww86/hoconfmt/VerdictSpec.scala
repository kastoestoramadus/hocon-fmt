package eu.ww86.hoconfmt

import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

/** What formatting makes of a file's content — the decision every integration acts on, so the
  * CLI and the build-tool plugins cannot disagree about a file.
  */
class VerdictSpec extends munit.FunSuite with HoconTestSupport {

  test("strict policy preserves name-based refusals even for valid HOCON content") {
    List("app.json" -> "JSON", "app.properties" -> "Java properties").foreach { case (name, format) =>
      List(false, true).foreach { strict =>
        assertEquals(
          Verdict.of("a = 1\n".getBytes(UTF_8), name, FormatOptions(failOnRefusal = strict)),
          Verdict.Refused(Refusal.OtherFormat(format))
        )
      }
    }
  }

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
      Refusal.ShadowedByInclude("o", 2, 1),
      Refusal.ReservedName,
      Refusal.UnstableOutput
    )
    refusals.foreach(r => assert(r.reason.nonEmpty, s"$r has no reason"))
  }

  // A lone surrogate escape is valid HOCON and renders as the raw character, which UTF-8 cannot
  // carry: writing the output replaced it with '?', so the file on disk no longer parsed.
  List(
    "a high half in a value"           -> "a = \"\\ud83d\"\n",
    "a low half in a value"            -> "a = \"\\udc00\"\n",
    "a quoted key"                     -> "\"\\ud83d\" = 1\n",
    "a low half in a key"              -> "\"\\udc00\" = 1\n",
    "reversed halves"                  -> "a = \"\\udc00\\ud83d\"\n",
    "a high half before ordinary text" -> "a = \"\\ud83dx\"\n"
  ).foreach { (name, source) =>
    test(s"an unpaired surrogate in $name is refused, not written as '?'") {
      List(Verdict.of(source), Verdict.of(source.getBytes(UTF_8))).foreach {
        case Verdict.Refused(Refusal.BrokenOutput(detail)) => assert(detail.contains("surrogate"), detail)
        case other                                         => fail(s"expected BrokenOutput, got $other")
      }
    }
  }

  // String callers can supply raw UTF-16 halves even though a UTF-8 file cannot contain them.
  List(
    "a value"                -> "a = \"\ud83d\"\n",
    "a key"                  -> "\"\udc00\" = 1\n",
    "a triple-quoted string" -> "a = \"\"\"\ud83d\"\"\"\n",
    "a comment"              -> "# \udc00\na = 1\n",
    "an include target"      -> "include \"\ud83d.conf\"\na = 1\n"
  ).foreach { (name, source) =>
    test(s"a raw unpaired surrogate in $name is refused by the text API") {
      Verdict.of(source) match {
        case Verdict.Refused(Refusal.BrokenOutput(detail)) => assert(detail.contains("surrogate"), detail)
        case other                                         => fail(s"expected BrokenOutput, got $other")
      }
    }
  }

  // The same characters where no escape is read: a paired escape, a triple-quoted string and a
  // comment hold text that survives UTF-8.
  List(
    "a paired escape"        -> "a = \"\\ud83d\\ude00\"\n",
    "a triple-quoted string" -> "a = \"\"\"\\ud83d\"\"\"\n",
    "a comment"              -> "# \\ud83d\na = 1\n",
    "an include target"      -> "include \"\\ud83d.conf\"\na = 1\n",
    "a raw pair"             -> "a = \"\ud83d\ude00\"\n"
  ).foreach { (name, source) =>
    test(s"$name with surrogate text is not mistaken for an unpaired surrogate") {
      Verdict.of(source.getBytes(UTF_8)) match {
        case Verdict.Refused(refusal) => fail(s"refused: ${refusal.reason}")
        case _                        => ()
      }
    }
  }
}
