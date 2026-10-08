package ww86.hocon_fmt

/** Tests of the shared suite that pin today's refusals and so cannot hold in a variant that
  * keeps more. The published core is the reference: nothing differs.
  *
  * UPSTREAM-SCONFIG: delete with the seam once ekrich/sconfig releases the option.
  */
object Variant {
  val ledger: Map[String, String] = Map.empty

  /** The fork base fixes the unresolved-object-merge rendering pinned by the shared suite. */
  val unresolvedMergesFormat: Boolean = false

  def differs(id: String): Boolean = ledger.contains(id)

  /** The examples `ExamplesSpec` pins as refused differently depending on the style options; the
    * published core is the reference. The page's fork renders some of them, hence the twin in
    * `core/site-shared`.
    */
  val refusedDifferently: List[(String, List[String])] = List(
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
}
