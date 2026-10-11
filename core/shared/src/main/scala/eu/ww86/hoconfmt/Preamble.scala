package eu.ww86.hoconfmt

import scala.annotation.tailrec

/** The leading block of comments and blank lines a file opens with, and where its body begins.
  *
  * Released sconfig drops a comment that a blank line separates from the field below it, so a
  * licence header, an SPDX line or a banner — the commonest shape in real files — would be lost.
  * The block is taken before anything is parsed and put back verbatim in front of the formatted
  * body, so nothing in it can be normalised, moved or dropped.
  *
  * The rule is deliberately narrow. The preamble is the run of blank and comment-only lines the
  * file opens with, less the comment block that sits directly above the first line of the body:
  * the parse attaches that block to the field below it, and taking it would move it away from the
  * field it belongs to. What is left must hold a comment, so a file that opens with blank lines
  * only has no preamble; and a file of nothing but comments has no body to format, so it keeps the
  * ordinary path and its refusal.
  *
  * [[HoconFormatter]] does not consult this where the parse already keeps detached comments (the
  * page's fork): the block survives the ordinary path there, through the carrier.
  */
private[hoconfmt] object Preamble {

  /** `preamble` is the file's own text, taken verbatim; `body` is what the ordinary pipeline
    * formats. [[blankLines]] is the same preamble written as blank lines, one per line, which the
    * parse reads in its place: comments and blank lines are both non-tokens, so the parse, the
    * rendering and every origin line of the body are the ones the file itself would give.
    */
  final case class Split(preamble: String, body: String) {
    def blankLines: String = "\n" * preamble.count(_ == '\n')
  }

  /** The split of a source that opens with a detached comment block, or `None` when its leading
    * block is one today's parse needs: none, blank lines only, or comments that all sit directly
    * above the first field of the body.
    */
  def split(source: String): Option[Split] = {
    val (leading, bodyAt) = leadingRun(source, Vector.empty, 0)
    // The comments the parse attaches to the first field: the trailing comment-only lines of the
    // run, which the body keeps so the attachment does not change. When the run of comment-only
    // lines is empty, what the body keeps is the body itself, which starts where the run stopped.
    val attachedFrom = leading.lastIndexWhere(_.kind != LineKind.CommentOnly) + 1
    val head         = leading.take(attachedFrom)
    Option.when(bodyAt >= 0 && head.exists(_.kind == LineKind.CommentOnly)) {
      val cut = if (attachedFrom < leading.length) leading(attachedFrom).start else bodyAt
      Split(source.substring(0, cut), source.substring(cut))
    }
  }

  private enum LineKind derives CanEqual { case Blank, CommentOnly, Content }

  /** A line the text opens with: where it starts, and what it holds. */
  final private case class Line(start: Int, kind: LineKind)

  /** The lines the text opens with, up to the first line that is neither blank nor comment-only,
    * and where that line starts (`-1` when the text holds no line of content).
    *
    * The scan stops at the first line of content and reads a comment-only line from its own first
    * characters, so a large file pays for its header and no more. The reading is exact for the
    * region it covers: no string can be open before the first line of content, so a `#` or `//`
    * that opens a line there really opens a comment, and anything else is content.
    */
  @tailrec
  private def leadingRun(source: String, lines: Vector[Line], from: Int): (Vector[Line], Int) =
    if (from >= source.length) (lines, -1)
    else {
      val end  = source.indexOf('\n', from) match { case -1 => source.length; case at => at }
      val kind = kindOf(source, from, end)
      if (kind == LineKind.Content) (lines, from)
      else leadingRun(source, lines :+ Line(from, kind), end + 1)
    }

  private def kindOf(source: String, start: Int, end: Int): LineKind =
    firstNonBlank(source, start, end) match {
      case None                                 => LineKind.Blank
      case Some(at) if opensComment(source, at) => LineKind.CommentOnly
      case Some(_)                              => LineKind.Content
    }

  private def opensComment(source: String, at: Int): Boolean =
    source.charAt(at) == '#' || source.startsWith("//", at)

  private def firstNonBlank(source: String, start: Int, end: Int): Option[Int] =
    (start until end).find(at => !isBlank(source.charAt(at)))

  private def isBlank(char: Char): Boolean = char == ' ' || char == '\t' || char == '\r'
}
