package ww86.hocon_fmt

import org.ekrich.config.ConfigObject

/** Text with something carried across the parse-render round trip: `text` is what sconfig parses,
  * `restore` puts back, in the rendered text, what was taken out, and `collides` judges a tree
  * parsed from `text` for user text that could pass for what was taken out.
  *
  * UPSTREAM-SCONFIG: exists for the `CommentCarrier` seam; delete with it (docs/site.md).
  */
final private[hocon_fmt] case class Carried(
    text: String,
    restore: String => String,
    collides: ConfigObject => Boolean
)
