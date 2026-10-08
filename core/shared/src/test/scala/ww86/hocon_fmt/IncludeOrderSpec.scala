package ww86.hocon_fmt

import ww86.hocon_fmt.HoconFormatter.format

/** An include is a field like any other: a later definition wins, so what comes before it decides
  * which value a key resolves to. sconfig orders fields by the line they start on and leaves
  * fields sharing a line in no defined order (`SconfigDefectsSpec`), and a key defined twice is
  * rendered once, where it first appeared. Either can carry a field across an include, which
  * changes what the file resolves to, so the formatter must refuse.
  */
class IncludeOrderSpec extends munit.FunSuite with HoconTestSupport {

  // `defaults.conf` holds `zone = "eu"`: written first, the include is overridden by `zone`.
  val moved = Map(
    "a field after the include on its line" -> (
      """include "defaults.conf", zone = "us"""",
      """include "defaults.conf""""
    ),
    "a field after the include in an object" -> (
      """app { include "defaults.conf", zone = "us" }""",
      """include "defaults.conf""""
    ),
    "fields on both sides of the include on its line" -> (
      """a = 1, include "defaults.conf", b = 2""",
      """include "defaults.conf""""
    ),
    "a function-form include followed by a field" -> (
      """include required(file("defaults.conf")), zone = "us"""",
      """include required(file("defaults.conf"))"""
    ),
    "two includes on one line" -> (
      """include "a.conf", include "b.conf"""",
      """include "a.conf""""
    ),
    "a key defined again after the include" -> (
      "a.b = 1\ninclude \"defaults.conf\"\na.c = 2",
      """include "defaults.conf""""
    )
  )

  moved.foreach { case (name, (raw, statement)) =>
    test(s"refuses $name") {
      assertEquals(refusalOf(raw), Refusal.MovedInclude(statement))
    }
  }

  val kept = Map(
    "the include on its own line, first" -> (
      "include \"defaults.conf\"\nzone = \"us\"",
      "include \"defaults.conf\"\nzone: us\n"
    ),
    "the include on its own line, last" -> (
      "zone = \"us\"\ninclude \"defaults.conf\"",
      "zone: us\ninclude \"defaults.conf\"\n"
    ),
    "an include in an object, own lines" -> (
      "app {\n  include \"defaults.conf\"\n  zone = \"us\"\n}",
      "app {\n  include \"defaults.conf\"\n  zone: us\n}\n"
    ),
    "a field before the include on its line" -> (
      "zone = \"us\", include \"defaults.conf\"",
      "zone: us\ninclude \"defaults.conf\"\n"
    ),
    "an include alone in its object" -> ("app { include \"defaults.conf\" }", "app {\n  include \"defaults.conf\"\n}\n")
  )

  kept.foreach { case (name, (raw, expected)) =>
    test(s"formats $name") {
      assertEquals(formatted(raw), expected)
    }
  }
}
