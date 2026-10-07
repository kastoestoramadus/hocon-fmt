package ww86.hocon_fmt.site

/** The snapshot's prose carries `backticked` fragments, the way the notes and the defect rows are
  * written; this is the one place that decides which words are code, so every part of the page
  * renders them the same way.
  */
object Prose:

  enum Part:
    case Code(text: String)
    case Text(text: String)

  def parts(text: String): List[Part] = Nil
