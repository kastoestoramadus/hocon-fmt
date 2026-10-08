package ww86.hocon_fmt

class ExamplesSpec extends munit.FunSuite {

  /** The kind an example's `now` and `pending` carry for each refusal. Exhaustive on purpose: the
    * compiler warns when a `Refusal` case is missing here, so a new case cannot drift past this
    * suite the way `MovedInclude` did.
    */
  def kindOf(refusal: Refusal): String = refusal match {
    case Refusal.NotUtf8         => "not-utf8"
    case Refusal.NotHocon(_)     => "not-hocon"
    case Refusal.OtherFormat(_)  => "other-format"
    case Refusal.BrokenOutput(_) => "broken-output"
    case Refusal.LostComment(_)  => "lost-comment"
    case Refusal.LostInclude(_)  => "lost-include"
    case Refusal.MovedInclude(_) => "moved-include"
    case Refusal.ReservedName    => "reserved-name"
    case Refusal.UnstableOutput  => "unstable-output"
  }

  test("every refusal maps to a kind the generator accepts") {
    // `Refusal.values` is not defined for enums with non-singleton cases ("a values array is not
    // defined"), so one sample instance per case stands in; a case missing from the list still
    // trips the exhaustive match in `kindOf` above.
    val cases = List(
      Refusal.NotUtf8,
      Refusal.NotHocon("detail"),
      Refusal.OtherFormat("JSON"),
      Refusal.BrokenOutput("detail"),
      Refusal.LostComment("# gone"),
      Refusal.LostInclude("include \"x.conf\""),
      Refusal.MovedInclude("include \"x.conf\""),
      Refusal.ReservedName,
      Refusal.UnstableOutput
    )
    assertEquals(cases.map(kindOf).toSet, ExampleData.kinds)
  }

  test("examples have an explicit reason for every roadmap gap") {
    ExampleData.all.foreach { example =>
      assert(
        example.now == example.target || example.reasonIfDifferent.exists(_.trim.nonEmpty),
        s"${example.id}: now differs from target without reason-if-different"
      )
    }
    val roadmap = ExampleData.all.filter(e => e.now != e.target)
    println("Examples roadmap (now / pending / target):")
    roadmap.foreach { e =>
      println(s"${e.id}: ${e.now} / ${e.pending.getOrElse("—")} / ${e.target}: ${e.reasonIfDifferent.getOrElse("")}")
    }
  }

  ExampleData.all.foreach { example =>
    example.options.foreach { option =>
      test(s"${example.id} ($option): ${example.now}") {
        val verdict = Verdict.of(example.input)
        val actual  = verdict match {
          case Verdict.NeedsFormatting(_) => "formatted"
          case Verdict.AlreadyFormatted   => "already-formatted"
          case Verdict.Refused(refusal)   => s"refused:${kindOf(refusal)}"
        }
        assertEquals(actual, example.now)
        verdict match {
          case Verdict.NeedsFormatting(output)                          => assertEquals(output, example.expected(option))
          case Verdict.AlreadyFormatted                                 => assertEquals(example.input, example.expected(option))
          case Verdict.Refused(_) if example.id.startsWith("showcase/") => ()
          case Verdict.Refused(_)                                       => assertEquals(example.expected(option).trim, example.now)
        }
      }
    }
  }

  // A refusal can depend on the style asked for: sconfig renders the same tree differently with
  // and without simplified nesting, and a defect that shows in one rendering may not in the other.
  // This pins which examples are refused differently by option, so a change in either direction is
  // seen; docs/limitations.md explains why.
  test("which examples are refused differently depending on the options") {
    val allOptions =
      for {
        separator <- List(Separator.Equals, Separator.Colon)
        double    <- List(false, true)
        simplify  <- List(true, false)
      } yield FormatOptions(separator, double, simplify)
    val differing = ExampleData.all.flatMap { example =>
      val outcomes = allOptions.map { options =>
        HoconFormatter.format(example.input, options).fold(r => s"refused:${kindOf(r)}", _ => "formatted")
      }
      val bySimplify = allOptions.zip(outcomes).groupMap(_._1.simplifyNestedObjects)(_._2).view.mapValues(_.toSet).toMap
      Option.when(outcomes.toSet.size > 1)(
        example.id -> bySimplify.toList.sortBy(!_._1).map((k, v) => s"simplify=$k: ${v.toList.sorted.mkString(",")}")
      )
    }
    assertEquals(
      differing,
      List(
        "showcase/05-sconfig-defect" -> List(
          "simplify=true: refused:broken-output",
          "simplify=false: refused:unstable-output"
        ),
        "catalogue/env-override-root-not-parseable" -> List(
          "simplify=true: refused:broken-output",
          "simplify=false: refused:unstable-output"
        ),
        "catalogue/object-substitution-then-field" -> List(
          "simplify=true: refused:broken-output",
          "simplify=false: formatted"
        )
      )
    )
  }

  test("showcase follows directory order and excludes catalogue") {
    assertEquals(ExampleData.showcase.map(_.id), ExampleData.showcase.map(_.id).sorted)
    assert(ExampleData.showcase.forall(_.id.startsWith("showcase/")))
  }
}
