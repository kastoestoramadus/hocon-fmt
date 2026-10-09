package ww86.hoconfmt

import org.ekrich.config.ConfigParseOptions

/** The seam through which the project page keeps comments that released sconfig drops (see
  * `core/site-shared`). Here it does nothing, so the published core parses and masks exactly as
  * it always has, with no switch to flip at run time.
  *
  * UPSTREAM-SCONFIG: the page only differs because of the sconfig fork. Once ekrich/sconfig
  * releases `setKeepDetachedComments` (#646/#647), delete this file, `Carried`,
  * `core/site-shared`, `coreSite` and the calls in `HoconFormatter`; docs/site.md lists the rest.
  */
private[hoconfmt] object CommentCarrier {
  def parseOptions(base: ConfigParseOptions): ConfigParseOptions = base

  def mask(source: String): Carried = Carried(source, identity, _ => false)
}
