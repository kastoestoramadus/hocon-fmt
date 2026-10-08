package ww86.hocon_fmt

/** Tests of the shared suite that pin today's refusals and so cannot hold in a variant that
  * keeps more. The published core is the reference: nothing differs.
  *
  * UPSTREAM-SCONFIG: delete with the seam once ekrich/sconfig releases the option.
  */
object Variant {
  val ledger: Map[String, String] = Map.empty

  def differs(id: String): Boolean = ledger.contains(id)
}
