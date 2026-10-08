package ww86.hocon_fmt

/** User text that looks like the placeholders `IncludeMasking` writes. The formatter may refuse such
  * a file, but a file it formats must still carry the text.
  */
class IncludeCollisionSpec extends munit.FunSuite {

  val allOptions: List[FormatOptions] =
    for {
      separator             <- List(Separator.Equals, Separator.Colon)
      doubleIndent          <- List(false, true)
      simplifyNestedObjects <- List(true, false)
    } yield FormatOptions(separator, doubleIndent, simplifyNestedObjects)

  val include = "include \"f.conf\"\n"

  // (name, source, text that must survive when the formatter accepts the file)
  val cases = List(
    ("guard text in a string", include + "a = \"prefix __INCLUDE_GUARD_0 = g suffix\"", "__INCLUDE_GUARD_0 = g"),
    ("guard text with a colon in a string", include + "a = \"x __INCLUDE_GUARD_0 : g y\"", "__INCLUDE_GUARD_0 : g"),
    ("guard text in a quoted key", include + "\"prefix __INCLUDE_GUARD_0 = g suffix\" = 42", "__INCLUDE_GUARD_0 = g"),
    ("placeholder text in a string", include + "a = \"__INCLUDE_0 = __INCLUDE_0\"", "__INCLUDE_0 = __INCLUDE_0"),
    ("a user field named like the placeholder", include + "__INCLUDE_0 = 5", "__INCLUDE_0"),
    ("a user field named like the guard", include + "__INCLUDE_GUARD_0 = g", "__INCLUDE_GUARD_0"),
    ("an index beyond Int", include + "a = \"__INCLUDE_GUARD_999999999999999 = g\"", "__INCLUDE_GUARD_999999999999999"),
    (
      "a placeholder beyond Int",
      include + "a = \"__INCLUDE_99999999999 : __INCLUDE_99999999999\"",
      "__INCLUDE_99999999999"
    ),
    (
      "a nested include",
      "o { include \"f.conf\"\n  a = \"__INCLUDE_GUARD_0 = g\" }",
      "__INCLUDE_GUARD_0 = g"
    ),
    ("an escaped underscore", include + "a = \"\\u005f_INCLUDE_GUARD_0 = g\"", "INCLUDE_GUARD_0 = g")
  )

  for {
    (name, source, kept) <- cases
    options              <- allOptions
  }
    test(
      s"$name: refused or kept, never altered (${options.separator}, ${options.doubleIndent}, ${options.simplifyNestedObjects})"
    ) {
      HoconFormatter.format(source, options).foreach { out =>
        assert(out.contains(kept), s"[$kept] changed in:\n$out")
        assert(out.contains("include \"f.conf\""), s"include lost from:\n$out")
      }
    }
}
