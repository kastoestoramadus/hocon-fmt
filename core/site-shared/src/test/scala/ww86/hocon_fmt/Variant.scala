package ww86.hocon_fmt

/** Tests of the shared suite that pin today's refusals; the page keeps these comments, so they are
  * left out here and `CommentCarrierSpec` pins the new outcome instead. The reason is the value.
  *
  * UPSTREAM-SCONFIG: delete with the seam once ekrich/sconfig releases the option.
  */
object Variant {
  private val fork = "the fork's base renders this where released sconfig 1.12.4 breaks (unmerged, quoted keys)"

  val ledger: Map[String, String] = Map(
    "showcase/03-dev-prod"                                                               -> fork,
    "showcase/04-library-file"                                                           -> "the detached licence header is kept",
    "catalogue/detached-header-comment"                                                  -> "the banner above a blank line is kept",
    "catalogue/trailing-comment-in-object"                                               -> "the comment before the closing brace is kept",
    "commentAboveBlankLine: a header followed by a blank line"                           -> "the header is kept",
    "commentAboveBlankLine: a comment block split by a blank line"                       -> "both halves are kept",
    "catalogue/sconfig-defect"                                                           -> fork,
    "catalogue/env-override-root-not-parseable"                                          -> fork,
    "catalogue/env-override-unresolved-merge"                                            -> fork,
    "catalogue/env-variable-list-suffix"                                                 -> "the fork parses the list suffix of an optional substitution",
    "catalogue/object-substitution-then-field"                                           -> fork,
    "mustRefuse: += field separator"                                                     -> fork,
    "mustRefuse: += field separator, nested"                                             -> fork,
    "mustRefuse: self-referential substitution"                                          -> fork,
    "mustRefuse: array self-concatenation"                                               -> fork,
    "specSelfReference: substitution cycle (Examples of Self-Referential Substitutions)" -> fork
  )

  /** The fork base fixes the unresolved-object-merge rendering pinned by the shared suite. */
  val unresolvedMergesFormat: Boolean = true

  def differs(id: String): Boolean = ledger.contains(id)

  /** The examples `ExamplesSpec` pins as refused differently depending on the style options; with
    * the fork every one of them renders whatever the options, so nothing differs here.
    */
  val refusedDifferently: List[(String, List[String])] = Nil
}
