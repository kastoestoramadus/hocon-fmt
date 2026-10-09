package ww86.hoconfmt.site

/** The snapshot's prose carries `backticked` fragments, the way the notes and the defect rows are
  * written; this is the one place that decides which words are code, so every part of the page
  * renders them the same way.
  */
object Prose {

  enum Part {
    case Code(text: String)
    case Text(text: String)
  }

  /** A fragment that is empty carries nothing — and an empty code part would draw an empty box
    * on the page — so the parts that mean something are the ones that come back.
    */
  def parts(text: String): List[Part] =
    // `split` keeps the trailing empty field, so an unclosed backtick at the very end still
    // flips the parity of what follows it instead of being swallowed.
    text
      .split("`", -1)
      .zipWithIndex
      .toList
      .flatMap { case (part, i) =>
        if part.isEmpty then None
        else if i % 2 == 1 then Some(Part.Code(part))
        else Some(Part.Text(part))
      }
}
