package ww86.hocon_fmt.site

import scala.scalajs.js

/** One pull request as the live GitHub search reports it, stripped to what the page shows. */
final case class LivePr(number: Int, title: String, state: LiveState)

/** The search API tells a merge from a rejection only through `pull_request.merged_at`. */
enum LiveState:
  case Open
  case Merged
  case ClosedUnmerged

object LivePr:

  /** Reads one `items` entry of the search/issues response. Anything the page cannot interpret —
    * a missing number or title, or no `pull_request` part, which is an issue — is dropped, never
    * guessed.
    */
  def read(item: js.Dynamic): Option[LivePr] =
    for
      number <- asInt(item.number)
      title  <- asString(item.title)
      state  <- readState(item)
    yield LivePr(number, title, state)

  private def readState(item: js.Dynamic): Option[LiveState] =
    val pr = item.pull_request
    if js.isUndefined(pr) || (pr: Any) == null then None
    else
      asString(item.state).flatMap {
        case "open"   => Some(LiveState.Open)
        case "closed" =>
          val mergedAt = pr.merged_at
          if js.isUndefined(mergedAt) || (mergedAt: Any) == null then Some(LiveState.ClosedUnmerged)
          else Some(LiveState.Merged)
        case _ => None
      }

  private[site] def asInt(v: js.Dynamic): Option[Int] =
    if js.typeOf(v) != "number" then None
    else
      val d = v.asInstanceOf[Double]
      if d.isValidInt then Some(d.toInt) else None

  private[site] def asString(v: js.Dynamic): Option[String] =
    if js.typeOf(v) == "string" then Some(v.asInstanceOf[String]) else None

/** The snapshot updated with what the live search says. Pure, so the page shows the same thing
  * offline as online and the rules have tests.
  */
final case class MergeResult(entries: List[Contribution], others: List[LivePr])

object Merge:

  /** Snapshot entries keep their place; a live result may only raise `Open` to merged or closed.
    * `Released` and `Closed` stand, because the search API knows less than the snapshot there.
    * Live pull requests missing from the snapshot come back as `others`, newest first.
    */
  def apply(snapshot: List[Contribution], live: List[LivePr]): MergeResult =
    val byNumber = live.map(pr => pr.number -> pr).toMap
    val entries  = snapshot.map { entry =>
      byNumber.get(entry.number) match {
        case Some(LivePr(_, _, LiveState.Merged)) if entry.state == PrState.Open =>
          entry.copy(state = PrState.MergedUnreleased)
        case Some(LivePr(_, _, LiveState.ClosedUnmerged)) if entry.state == PrState.Open =>
          entry.copy(state = PrState.Closed)
        case _ => entry
      }
    }
    val known  = snapshot.map(_.number).toSet
    val others = live.filterNot(pr => known.contains(pr.number)).sortBy(-_.number)
    MergeResult(entries, others)
