package eu.ww86.hoconfmt

/** Tests of the shared suite that pin today's refusals and so cannot hold in a variant that
  * keeps more. The published core is the reference: nothing differs.
  *
  * UPSTREAM-SCONFIG: delete with the seam once ekrich/sconfig releases the option.
  */
object Variant {
  val ledger: Map[String, String] = Map.empty

  /** The document engine keeps unresolved object merges as written, like the fork base did. */
  val unresolvedMergesFormat: Boolean = true

  def differs(id: String): Boolean = ledger.contains(id)

  /** The examples `ExamplesSpec` pins as refused differently depending on the style options; the
    * published core is the reference. The page's fork renders some of them, hence the twin in
    * `core/site-shared`.
    */
  val refusedDifferently: List[(String, List[String])] = List(
    "catalogue/env-override-root-not-parseable" -> List(
      "simplify=true: refused:broken-output",
      "simplify=false: refused:unstable-output"
    ),
    "catalogue/object-substitution-then-field" -> List(
      "simplify=true: refused:broken-output",
      "simplify=false: formatted"
    ),
    "catalogue/sconfig-defect" -> List(
      "simplify=true: refused:broken-output",
      "simplify=false: refused:unstable-output"
    )
  )
}
