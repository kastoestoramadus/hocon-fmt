package eu.ww86.hoconfmt

/** An include is a field like any other: a later definition wins, so what comes before it decides
  * which value a key resolves to. The document engine keeps every statement where it stands, so
  * an include can no longer change places with a field — the order check (`MovedInclude`) stays
  * as a guard and these fixtures pin the order the engine actually keeps. sconfig's shared-line
  * ordering quirks remain pinned in `SconfigDefectsSpec`.
  */
class IncludeOrderSpec extends munit.FunSuite with HoconTestSupport {

  // `defaults.conf` holds `zone = "eu"`: written first, the include is overridden by `zone`.
  // None of these move any more: the include stays in front of whatever shared its line, which
  // is the order the file resolves to.
  val keptInPlace = Map(
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
    "a field after the include, in an object in an array" -> (
      """arr = [ { include "f.conf", a = 2 } ]""",
      """include "f.conf""""
    ),
    "a key defined again after the include, in an object in an array" -> (
      "arr = [\n{\n o.a = 1\n include \"g.conf\"\n o.c = 2\n}\n]\n",
      """include "g.conf""""
    ),
    "a key defined again after the include" -> (
      "a.b = 1\ninclude \"defaults.conf\"\na.c = 2",
      """include "defaults.conf""""
    ),
    "an empty object the include would cross" -> (
      "p { a = 1 }\ninclude \"defaults.conf\"\np.q {}",
      """include "defaults.conf""""
    ),
    "an empty object the include would cross in an object" -> (
      "a {\n  p { a = 1 }\n  include \"defaults.conf\"\n  p.q {}\n}\n",
      """include "defaults.conf""""
    )
  )

  keptInPlace.filterNot((name, _) => Variant.differs(s"includeOrder: $name")).foreach {
    case (name, (raw, statement)) =>
      test(s"formats $name, include in place") {
        val out = formatted(raw)
        assert(out.contains(statement), s"[$name] the include statement left the output: $out")
        assertEquals(formatted(out), out, "the second pass must keep the output")
      }
  }

  def kept = Map(
    "the include on its own line, first" -> (
      "include \"defaults.conf\"\nzone = \"us\"",
      "include \"defaults.conf\"\nzone = \"us\"\n"
    ),
    "the include on its own line, last" -> (
      "zone = \"us\"\ninclude \"defaults.conf\"",
      "zone = \"us\"\ninclude \"defaults.conf\"\n"
    ),
    "an include in an object, own lines" -> (
      "app {\n  include \"defaults.conf\"\n  zone = \"us\"\n}",
      "app {\n  include \"defaults.conf\"\n  zone = \"us\"\n}\n"
    ),
    "a field before the include on its line" -> (
      "zone = \"us\", include \"defaults.conf\"",
      "zone = \"us\", include \"defaults.conf\"\n"
    ),
    "an include alone in its object" -> (
      "app { include \"defaults.conf\" }",
      "app { include \"defaults.conf\" }\n"
    ),
    "an empty object before the include" -> (
      "p.q {}\ninclude \"defaults.conf\"",
      "p.q {}\ninclude \"defaults.conf\"\n"
    ),
    "an empty object after the include" -> ("include \"defaults.conf\"\np.q {}", "include \"defaults.conf\"\np.q {}\n")
  )

  kept.foreach { case (name, (raw, expected)) =>
    test(s"formats $name") {
      assertEquals(formatted(raw), expected)
    }
  }
}
