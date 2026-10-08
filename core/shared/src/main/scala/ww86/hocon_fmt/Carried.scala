package ww86.hocon_fmt

/** Text with something carried across the parse-render round trip: `text` is what sconfig parses,
  * `restore` puts back, in the rendered text, what was taken out.
  */
private[hocon_fmt] final case class Carried(text: String, restore: String => String)
