package eu.ww86.hoconfmt

/** Tests of the shared suite that pin today's refusals; the page keeps these comments, so they are
  * left out here and `CommentCarrierSpec` pins the new outcome instead. The reason is the value.
  *
  * UPSTREAM-SCONFIG: delete with the seam once ekrich/sconfig releases the option.
  */
object Variant {
  private val fork = "the fork's base renders this where released sconfig 2.0.0 breaks (unmerged, quoted keys)"

  val ledger: Map[String, String] = Map(
    "catalogue/include-sharing-a-line"                                                   -> "#645 preserves same-line field order",
    "includeOrder: a field after the include on its line"                                -> "#645 preserves same-line field order",
    "includeOrder: a field after the include in an object"                               -> "#645 preserves same-line field order",
    "includeOrder: fields on both sides of the include on its line"                      -> "#645 preserves same-line field order",
    "includeOrder: a function-form include followed by a field"                          -> "#645 preserves same-line field order",
    "includeOrder: two includes on one line"                                             -> "#645 preserves same-line field order",
    "includeOrder: a field after the include, in an object in an array"                  -> "#645 preserves same-line field order",
    "duplicatedComment: on an appended field"                                            -> "#601 renders the appended comment once",
    "duplicatedComment: on an appended field, in an object"                              -> "#601 renders the appended comment once",
    "numeric: Infinity"                                                                  -> "#634 changes overflowing-number rendering; the fixed-point guard refuses",
    "showcase/03-dev-prod"                                                               -> fork,
    "showcase/04-library-file"                                                           -> "the detached licence header is kept",
    "catalogue/detached-array-comment"                                                   -> "the detached array-element comment is kept",
    "catalogue/unresolved-merge-in-array"                                                -> fork,
    "catalogue/detached-header-comment"                                                  -> "the fork's carrier keeps the banner without the blank line the command line's preamble path keeps verbatim",
    "catalogue/trailing-comment-in-object"                                               -> "the comment before the closing brace is kept",
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
    "mustRefuse: nested self-reference"                                                  -> fork,
    "mustRefuse: env override in an object"                                              -> fork,
    "mustRefuse: env override in an object with a sibling after the object"              -> fork,
    "mustRefuse: self-reference in an object with a sibling after the object"            -> fork,
    "mustRefuse: nested self-reference with a trailing field"                            -> fork,
    "unstableMerge: self-referential substitution with a sibling field"                  -> fork,
    "unstableMerge: += field separator with a sibling field"                             -> fork,
    "unstableMerge: env override with a sibling field"                                   -> fork,
    "unstableMerge: env override in an object with a sibling field"                      -> fork,
    "unstableMerge: self-referential substitution with a field before"                   -> fork,
    "unstableMerge: self-referential substitution with a leading comment"                -> fork,
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
