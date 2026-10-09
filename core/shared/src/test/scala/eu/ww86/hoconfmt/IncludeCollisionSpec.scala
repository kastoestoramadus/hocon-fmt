package eu.ww86.hoconfmt

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

  val concatenatedCases = for {
    index    <- List(0, 1)
    spelling <- List(
                  s"\"__INCL\"\"UDE_GUARD_$index\" = \"g\"",
                  s"\"__INCL\"\"UDE_$index\" = \"__INCL\"\"UDE_$index\"",
                  s"\"__INCL\"UDE_GUARD_$index = g"
                )
    nested <- List(false, true)
  } yield {
    val includes = include + (if (index == 1) "include \"second.conf\"\n" else "")
    val body     = includes + spelling + "\n"
    if (nested) s"o {\n$body}\n" else body
  }

  val concatenatedValues = for {
    token <- List("\"__INCL\"\"UDE_0\"", "\"__INCL\"UDE_GUARD_0")
    field <- List(s"a = $token", s"a = [$token]", s"o { a = $token }")
  } yield include + field + "\n"

  for {
    source  <- concatenatedCases ++ concatenatedValues ++ List(include + "a = ${\"__INCL\"\"UDE_0\"}\n")
    options <- allOptions
  } test(s"concatenated reserved spelling: $source ($options)") {
    assertEquals(HoconFormatter.format(source, options), Left(Refusal.ReservedName))
  }

  // --- The probe prefix -------------------------------------------------------------------------
  // `collisionProbe` masks the source again under a prefix family no user field can spell, so a
  // mimic that overwrote a generated field shows up as the user's. It must take the first index
  // absent from both texts, found by one scan per text: asking `contains` per candidate index
  // rescanned both texts once per index, which a comment listing thousands of them turned
  // quadratic.

  test("the probe prefix is the first index neither text spells") {
    val probe = IncludeMasking.collisionProbe(include, "# __HOCON_MASK_0_ and __HOCON_MASK_2_\n")
    assert(probe.text.contains("__HOCON_MASK_1_0 :"), probe.text)
  }

  test("a leading zero does not hide the index it spells") {
    val probe = IncludeMasking.collisionProbe(include, "# __HOCON_MASK_01_\n")
    assert(probe.text.contains("__HOCON_MASK_0_0 :"), probe.text)
  }

  test("an index spelled in the source is skipped too") {
    val probe = IncludeMasking.collisionProbe(include + "# __HOCON_MASK_0_\n", "")
    assert(probe.text.contains("__HOCON_MASK_1_0 :"), probe.text)
  }

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
