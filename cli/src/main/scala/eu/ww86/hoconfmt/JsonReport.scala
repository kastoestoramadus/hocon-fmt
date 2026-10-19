package eu.ww86.hoconfmt

import org.ekrich.config.{ConfigRenderOptions, ConfigValueFactory}

final case class JsonReport(
    version: String,
    checked: Int,
    changed: Int,
    refused: List[JsonReport.RefusedEntry],
    failed: List[JsonReport.FailedEntry]
) {
  def rendered: String = {
    val refusals = refused
      .sortBy(_.path)
      .map(entry => s"{\"path\":${JsonReport.quote(entry.path)},\"reason\":${JsonReport.quote(entry.reason)}}")
      .mkString(",")
    val failures = failed
      .sortBy(_.path)
      .map(entry => s"{\"path\":${JsonReport.quote(entry.path)},\"message\":${JsonReport.quote(entry.message)}}")
      .mkString(",")
    s"{\"version\":${JsonReport.quote(version)},\"checked\":$checked,\"changed\":$changed,\"refused\":[$refusals],\"failed\":[$failures]}\n"
  }
}

object JsonReport {
  final case class RefusedEntry(path: String, reason: String)
  final case class FailedEntry(path: String, message: String)

  def of(run: CmdApi.Run, version: String = BuildInfo.version): JsonReport = {
    val changed = run.outcomes.count(_.result match {
      case CmdApi.Result.NeedsFormatting(_) | CmdApi.Result.Rewritten => true
      case _                                                          => false
    })
    val refused = run.outcomes.collect {
      case outcome @ CmdApi.Outcome(CmdApi.Result.Unformattable(refusal), _, _, _, _, _) =>
        RefusedEntry(outcome.path, s"${kind(refusal)}: ${refusal.reason}")
    }
    val failed = run.outcomes.collect {
      case CmdApi.Outcome(CmdApi.Result.Unreadable(message), path, _, _, _, _) => FailedEntry(path, message)
      case CmdApi.Outcome(CmdApi.Result.Unwritable(message), path, _, _, _, _) => FailedEntry(path, message)
    }
    JsonReport(version, run.outcomes.size, changed, refused, failed)
  }

  def configurationFailure(checked: Int, failure: ConfigFile.Failure, version: String = BuildInfo.version): JsonReport =
    configurationFailures(checked, List(failure), version)

  def configurationFailures(
      checked: Int,
      failures: List[ConfigFile.Failure],
      version: String = BuildInfo.version
  ): JsonReport =
    JsonReport(version, checked, 0, Nil, failures.map(failure => FailedEntry(failure.path, failure.message)))

  def quote(value: String): String =
    ConfigValueFactory.fromAnyRef(value).render(ConfigRenderOptions.concise.setJson(true))

  private def kind(refusal: Refusal): String = refusal match {
    case Refusal.NotUtf8                    => "NotUtf8"
    case Refusal.NestingTooDeep(_)          => "NestingTooDeep"
    case Refusal.PathTooDeep(_)             => "PathTooDeep"
    case Refusal.SlowMerges(_)              => "SlowMerges"
    case Refusal.NotHocon(_)                => "NotHocon"
    case Refusal.OtherFormat(_)             => "OtherFormat"
    case Refusal.BrokenOutput(_)            => "BrokenOutput"
    case Refusal.LostComment(_)             => "LostComment"
    case Refusal.LostInclude(_)             => "LostInclude"
    case Refusal.MovedInclude(_)            => "MovedInclude"
    case Refusal.ShadowedByInclude(_, _, _) => "ShadowedByInclude"
    case Refusal.ReservedName               => "ReservedName"
    case Refusal.UnstableOutput             => "UnstableOutput"
  }
}
