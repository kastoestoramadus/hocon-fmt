package eu.ww86.hoconfmt

import org.ekrich.config.ConfigParseOptions

/** The seam through which the project page keeps comments that released sconfig drops (see
  * `core/site-shared`). Here it does nothing, so the published core parses and masks exactly as
  * it always has, with no switch to flip at run time; `keepsDetachedComments` says so, which is
  * what lets `HoconFormatter` take a detached leading block verbatim as a preserved preamble.
  *
  * UPSTREAM-SCONFIG: the page only differs because of the sconfig fork. Once ekrich/sconfig
  * releases `setKeepDetachedComments` (#646/#647), delete this file, `Carried`,
  * `core/site-shared`, `coreSite` and the calls in `HoconFormatter`; docs/site.md lists the rest.
  * The preamble path is not the fork's: it keeps what the released library drops, so it stays.
  */
private[hoconfmt] object CommentCarrier {

  /** Whether the parse options keep a comment a blank line detaches from the field below. False
    * here, so the published core's preamble path is the mechanism that keeps such a block.
    */
  val keepsDetachedComments: Boolean = false

  def parseOptions(base: ConfigParseOptions): ConfigParseOptions = base

  def mask(source: String): Carried = Carried(source, identity, _ => false)
}
