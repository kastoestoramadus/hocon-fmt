package eu.ww86.hoconfmt

import _root_.cats.effect.IO
import fs2.io.file.{Files, Path}

class JsonReportSpec extends munit.CatsEffectSuite {
  def golden(name: String): IO[String] =
    Files[IO].readUtf8(Path(s"cli/src/test/resources/reports/$name.json")).compile.string

  def outcome(result: CmdApi.Result, path: String): CmdApi.Outcome =
    CmdApi.Outcome(result, path, Nil, failOnDuplicates = false)

  test("empty JSON has exactly the committed schema") {
    golden("empty").map(expected => assertEquals(JsonReport.of(CmdApi.Run(Nil), "test").rendered, expected))
  }

  test("mixed JSON counts original check changes and writes and sorts diagnostics") {
    val run = CmdApi.Run(
      List(
        outcome(CmdApi.Result.Unwritable("cannot write"), "write.conf"),
        outcome(CmdApi.Result.NeedsFormatting("a = 1\n"), "check.conf"),
        outcome(CmdApi.Result.Rewritten, "changed.conf"),
        outcome(CmdApi.Result.AlreadyFormatted, "unchanged.conf"),
        outcome(CmdApi.Result.Unformattable(Refusal.OtherFormat("JSON")), "data.json"),
        outcome(CmdApi.Result.Unreadable("cannot read"), "missing.conf")
      )
    )
    golden("mixed").map(expected => assertEquals(JsonReport.of(run, "test").rendered, expected))
  }

  test("JSON retains escaped paths and complete refusal kinds and reasons") {
    val run =
      CmdApi.Run(List(outcome(CmdApi.Result.Unformattable(Refusal.LostComment("line\n\t✓")), "quote\"\\\n.conf")))
    golden("escaped").map(expected => assertEquals(JsonReport.of(run, "test").rendered, expected))
  }

  test("reporting does not alter exit policy or original results") {
    val run = CmdApi.Run(
      List(
        CmdApi.Outcome(
          CmdApi.Result.Unformattable(Refusal.OtherFormat("JSON")),
          "data.json",
          Nil,
          failOnDuplicates = false,
          failOnRefusal = true
        )
      )
    )
    val before = run.exitCode
    val _      = JsonReport.of(run, "test").rendered
    assertEquals(run.exitCode, before)
    assertEquals(JsonReport.of(run, "test").changed, 0)
  }

  test("configuration failures record the config path without counting it as an extra selected file") {
    val report =
      JsonReport.configurationFailure(3, ConfigFile.Failure("team.conf", "team.conf: unknown option: typo"), "test")
    assertEquals(report.checked, 3)
    assertEquals(report.changed, 0)
    assert(report.rendered.contains("\"path\":\"team.conf\",\"message\":\"team.conf: unknown option: typo\""))
  }
}
