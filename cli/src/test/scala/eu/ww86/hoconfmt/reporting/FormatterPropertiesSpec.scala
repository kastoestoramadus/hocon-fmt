package eu.ww86.hoconfmt.reporting

import eu.ww86.hoconfmt.{CmdApi, JsonReport, Refusal}
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

class FormatterPropertiesSpec extends munit.ScalaCheckSuite {
  val input = for {
    tag        <- Gen.choose(0, 7)
    text       <- Gen.alphaStr
    strict     <- Arbitrary.arbitrary[Boolean]
    duplicates <- Arbitrary.arbitrary[Boolean]
  } yield (tag, text, strict, duplicates)

  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(1000)

  property("reports preserve decisions and count every original file outcome") {
    forAll(Gen.listOf(input)) { entries =>
      val outcomes = entries.map { case (tag, text, strict, duplicates) =>
        val result = tag match {
          case 0 => CmdApi.Result.AlreadyFormatted
          case 1 => CmdApi.Result.Rewritten
          case 2 => CmdApi.Result.NeedsFormatting(text)
          case 3 => CmdApi.Result.Unformattable(Refusal.NotHocon(text))
          case 4 => CmdApi.Result.Unreadable(text)
          case 5 => CmdApi.Result.Unwritable(text)
          case 6 => CmdApi.Result.Unformattable(Refusal.OtherFormat(text))
          case _ => CmdApi.Result.Unformattable(Refusal.LostComment(text))
        }
        CmdApi.Outcome(result, text, Nil, failOnDuplicates = duplicates, failOnRefusal = strict)
      }
      val run    = CmdApi.Run(outcomes)
      val before = (run.exitCode, run.rendered, run.errors)
      val report = JsonReport.of(run, "test")
      val _      = report.rendered
      assertEquals(report.checked, entries.size)
      assertEquals(report.changed, entries.count(entry => entry._1 == 1 || entry._1 == 2))
      assertEquals(report.failed.size, entries.count(entry => entry._1 == 4 || entry._1 == 5))
      assertEquals(report.refused.size, entries.count(entry => entry._1 == 3 || entry._1 == 6 || entry._1 == 7))
      assertEquals((run.exitCode, run.rendered, run.errors), before)
    }
  }
}
