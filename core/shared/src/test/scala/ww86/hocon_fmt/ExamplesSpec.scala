package ww86.hocon_fmt

class ExamplesSpec extends munit.FunSuite {
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
          case Verdict.Refused(refusal)   =>
            val kind = refusal match {
              case Refusal.NotUtf8         => "not-utf8"
              case Refusal.NotHocon(_)     => "not-hocon"
              case Refusal.BrokenOutput(_) => "broken-output"
              case Refusal.LostComment(_)  => "lost-comment"
              case Refusal.LostInclude(_)  => "lost-include"
              case Refusal.UnstableOutput  => "unstable-output"
            }
            s"refused:$kind"
        }
        assertEquals(actual, example.now)
        verdict match {
          case Verdict.NeedsFormatting(output)                          => assertEquals(output, example.expected(option))
          case Verdict.AlreadyFormatted                                 => assertEquals(example.input, example.expected(option))
          case Verdict.Refused(_) if example.id.startsWith("showcase/") =>
            assertEquals(example.expected(option), example.input)
          case Verdict.Refused(_) => assertEquals(example.expected(option).trim, example.now)
        }
      }
    }
  }

  test("showcase follows directory order and excludes catalogue") {
    assertEquals(ExampleData.showcase.map(_.id), ExampleData.showcase.map(_.id).sorted)
    assert(ExampleData.showcase.forall(_.id.startsWith("showcase/")))
    assertEquals(ExampleData.showcase.size, 5)
    assertEquals(
      ExampleData.showcase.map(_.now),
      List("formatted", "formatted", "formatted", "refused:not-hocon", "refused:broken-output")
    )
  }
}
