package ww86.hocon_fmt.site

/** The two libraries whose pull requests the page lists. Both carry the author's real account,
  * which the live refresh searches; `repo` is the `owner/repo` the search API wants.
  */
enum Library(val repo: String, val label: String, val blurb: String):
  case Sconfig
      extends Library(
        "ekrich/sconfig",
        "sconfig",
        "the HOCON library for Scala, Scala.js and Scala Native that this formatter is built on"
      )
  case LightbendConfig
      extends Library(
        "lightbend/config",
        "lightbend/config",
        "the original HOCON library, which sconfig keeps in step with"
      )

/** Where an entry belongs on the page. The declaration order is the reading order, so the theme
  * the formatter lives from — unresolved merges, the round trip — comes first.
  */
enum Theme(val label: String):
  case FormatterProposal              extends Theme("making a formatter possible")
  case UnresolvedMerges               extends Theme("unresolved merges")
  case RendererRoundTrip              extends Theme("renderer round trip")
  case Comments                       extends Theme("comments")
  case SubstitutionsAndConcatenations extends Theme("substitutions and concatenations")
  case ParserLimitsAndNumbers         extends Theme("parser limits and number conversions")
  case Ports                          extends Theme("ports from lightbend/config")
  case EnvironmentOverrides           extends Theme("environment overrides")
  case Performance                    extends Theme("performance")
  case Project                        extends Theme("project and tooling")

/** A pull request's state as the snapshot recorded it. `Released` is snapshot knowledge only —
  * the search API cannot see a release — so a merge never overwrites it.
  */
enum PrState(val label: String):
  case Open             extends PrState("open")
  case MergedUnreleased extends PrState("merged upstream, not yet in a release")
  case Released         extends PrState("in a release")
  case Closed           extends PrState("closed without a merge")

/** One pull request of the snapshot: the number to link, the GitHub title for the refresh
  * script's diff, the theme it is listed under, and one sentence in domain terms on what was
  * wrong and what the PR fixes.
  */
final case class Contribution(
    library: Library,
    number: Int,
    title: String,
    theme: Theme,
    state: PrState,
    note: String
)

/** A defect the formatter refuses, tied to the pull requests that aim to fix it upstream. */
final case class DefectRow(
    defect: String,
    refusal: String,
    fixes: List[PrLink],
    whenNoFix: String
)

object DefectRow:
  val noFixYet = "no pull request yet"

/** A reference from a defect row into the snapshot, resolved for display and state. */
final case class PrLink(library: Library, number: Int)
