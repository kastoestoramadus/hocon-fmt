package eu.ww86.hoconfmt

/** Tests of the shared suite that pin today's refusals and so cannot hold in a variant that
  * keeps more. The document engine is shared with core: nothing differs.
  *
  * UPSTREAM-SCONFIG: delete with the seam once ekrich/sconfig releases the option.
  */
object Variant {
  val ledger: Map[String, String] = Map.empty

  /** The document engine keeps unresolved object merges as written, like the fork base did. */
  val unresolvedMergesFormat: Boolean = true

  def differs(id: String): Boolean = ledger.contains(id)

  /** The document engine formats every example whatever the options, like the fork did, so no
    * example is refused differently any more.
    */
  val refusedDifferently: List[(String, List[String])] = Nil
}
