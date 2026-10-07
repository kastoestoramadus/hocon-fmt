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
  def read(item: js.Dynamic): Option[LivePr] = ???

/** The snapshot updated with what the live search says. Pure, so the page shows the same thing
  * offline as online and the rules have tests.
  */
final case class MergeResult(entries: List[Contribution], others: List[LivePr])

object Merge:

  /** Snapshot entries keep their place; a live result may only raise `Open` to merged or closed.
    * `Released` and `Closed` stand, because the search API knows less than the snapshot there.
    * Live pull requests missing from the snapshot come back as `others`.
    */
  def apply(snapshot: List[Contribution], live: List[LivePr]): MergeResult = ???
