package eu.ww86.hoconfmt

/** Values sconfig cannot unwrap or enumerate before resolution, under an include.
  *
  * A merge that waits on a substitution cannot be enumerated (`entrySet` throws
  * `ConfigException.NotResolved`) or unwrapped, but it can be rendered, and its rendering exposes
  * the keys and values a restoration would land on. The reserved-name check has to judge such a
  * value that way; letting the exception reach `format` refuses valid HOCON with "need to
  * Config#resolve()", which blames the input for a library limitation.
  */
class UnresolvedMergeSpec extends munit.FunSuite {

  val allOptions: List[FormatOptions] =
    for {
      separator             <- List(Separator.Equals, Separator.Colon)
      doubleIndent          <- List(false, true)
      simplifyNestedObjects <- List(true, false)
    } yield FormatOptions(separator, doubleIndent, simplifyNestedObjects)

  val include = "include \"f.conf\"\n"

  // The specification's merge idiom: `a` is defined, then `a.b` merges into it, and the merge waits
  // on the substitution. Valid HOCON; sconfig renders it as a banner the pipeline cannot hand back,
  // which is the refusal the formatter has to give.
  val mergeUnderInclude = include + "o {\n  a = ${x}\n  a.b = 1\n}\n"

  // The same merge at the file root, where the banner says so in as many words.
  val mergeAtRoot = include + "a = ${x}\na.b = 1\n"

  // Rendering the merge exposes a reserved name spelled as adjacent tokens, which `unwrapped`
  // could never show: the string does not spell `__INCLUDE_` until sconfig concatenates it.
  val reservedInMerge = include + "o {\n  a = ${x}\n  a.b = [\"__INCL\"\"UDE_0\"]\n}\n"

  /** How the output refuses to come back: with simplified nesting the flattened banner does not
    * parse again, without it the banner parses but grows on the next pass. Both blame the output,
    * never the input; which one comes out depends on the options, as docs/limitations.md says a
    * refusal can, so each is pinned.
    */
  def outputRefusal(refusal: Refusal): String = refusal match {
    case Refusal.BrokenOutput(_) => "broken-output"
    case Refusal.UnstableOutput  => "unstable-output"
    case other                   => fail(s"refused for the wrong reason: ${other.reason}")
  }

  def refusalOf(source: String, options: FormatOptions)(using munit.Location): Refusal =
    HoconFormatter.format(source, options).swap.getOrElse(fail(s"expected a refusal, but the file formatted:\n$source"))

  for {
    (name, source) <- List("under an include" -> mergeUnderInclude, "at the root" -> mergeAtRoot)
    options        <- allOptions
  } test(s"an unresolved object merge $name follows the variant rendering ($options)") {
    if (Variant.unresolvedMergesFormat) {
      val formatted = HoconFormatter.format(source, options).fold(refusal => fail(refusal.reason), identity)
      assert(formatted.contains(include.trim), "the include must survive")
      assertEquals(HoconFormatter.format(formatted, options), Right(formatted), "the fork output must be stable")
    } else {
      val expected = if (options.simplifyNestedObjects) "broken-output" else "unstable-output"
      assertEquals(outputRefusal(refusalOf(source, options)), expected)
    }
  }

  for (options <- allOptions)
    test(s"a reserved name inside an unresolved merge is still refused as reserved ($options)") {
      assertEquals(HoconFormatter.format(reservedInMerge, options), Left(Refusal.ReservedName))
    }
}
