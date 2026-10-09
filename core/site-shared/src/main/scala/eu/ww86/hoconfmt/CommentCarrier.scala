package eu.ww86.hoconfmt

import java.util.regex.{Matcher, Pattern}

import org.ekrich.config.{ConfigObject, ConfigParseOptions}
import scala.annotation.tailrec
import scala.collection.mutable

/** The project page's half of the comment seam (the no-op half is in `core/default-shared`).
  *
  * The sconfig fork keeps a comment that a blank line separates from the field below it
  * (`setKeepDetachedComments`, ekrich/sconfig#646, draft #647). A comment no field follows is
  * still dropped, because there is nothing to attach it to: the last lines of an object, the end of
  * the file, a file of comments only. Those blocks are swapped for a placeholder field before the
  * parse and put back after the render, the way [[IncludeMasking]] carries includes.
  *
  * Runs after [[IncludeMasking]], so a comment above an include placeholder is carried like any
  * other. A block in an array or parentheses stays unmasked, since no field can stand there; the
  * formatter then refuses the file, as it does for any lost comment.
  *
  * User text is never altered. The prefix is chosen to occur nowhere the parse could put it: not
  * in the text, and not in its reading with quotes dropped and `\uXXXX` escapes resolved, where
  * `"__COMM""ENT_0"` and `"\u005f..."` spell a name this pass would otherwise write. The tree the
  * parse actually made is judged too, the way [[IncludeMasking]] judges its own
  * ([[PlaceholderTree]]), so a spelling the source reading missed refuses the file. And a rendered
  * text whose prefix occurrences are not exactly the ones the placeholders wrote is left alone:
  * the block is then missing from the output, and the formatter's lost-comment check refuses the
  * file rather than restore on a guess.
  *
  * The regexes must hold on RE2 and ES2015: no lookaround, no backreferences, no multiline `^`.
  * A character scanner like [[IncludeMasking]], written with loops; the suppression is for this
  * object only.
  *
  * UPSTREAM-SCONFIG: delete this file with the seam once ekrich/sconfig releases the option
  * (#646/#647). The test that signals it is `KeepDetachedCommentsGuardSpec`, "library: sconfig
  * keeps a comment a blank line detaches from the field below", which turns green on a sconfig
  * with the option; then follow docs/site.md, "Returning to upstream sconfig", and delete this
  * file, the call sites and `coreSite`.
  */
@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
private[hoconfmt] object CommentCarrier {

  private val CommentPrefix = "__COMMENT_"

  /** How often each placeholder writes its prefix: the key, the value and the guard's key. */
  private val PrefixPerPlaceholder = 3

  def parseOptions(base: ConfigParseOptions): ConfigParseOptions = base.setKeepDetachedComments(true)

  def mask(source: String): Carried = {
    val prefix = prefixFor(source)
    val blocks = blocksOf(source)
    if (blocks.isEmpty) Carried(source, identity, _ => false)
    else {
      val masked = new StringBuilder
      var copied = 0
      blocks.zipWithIndex.foreach { case ((start, end, _), index) =>
        val _ = masked.append(source.substring(copied, start)).append(placeholderFor(prefix, index))
        copied = end
      }
      val _         = masked.append(source.substring(copied))
      val originals = blocks.zipWithIndex.map { case ((_, _, block), index) => index -> block }.toMap
      Carried(masked.result(), restore(_, prefix, originals), collides(_, prefix, originals.keySet))
    }
  }

  /** The same judgement [[IncludeMasking]] makes of its tree: a reserved name is the user's unless
    * it is exactly a key/value pair this pass wrote. `prefixFor` keeps the prefix out of every
    * spelling of the source, so a hit here is a spelling that reading could not see, and refusing
    * is the only safe answer.
    */
  private def collides(parsed: ConfigObject, prefix: String, indices: Set[Int]): Boolean =
    PlaceholderTree.collides(
      parsed,
      prefix,
      indices.iterator.flatMap { index =>
        List(s"$prefix$index" -> s"$prefix$index", s"${prefix}GUARD_$index" -> GuardValue)
      }.toMap
    )

  /** The first prefix the parse cannot spell from the text: `__COMMENT_` when the text does not
    * mention it, `__COMMENTX_` and so on otherwise. Each step appends an `X`, so a finite text
    * always leaves one.
    */
  @tailrec
  private def prefixFor(source: String, prefix: String = CommentPrefix): String =
    if (mentions(source, prefix)) prefixFor(source, prefix.dropRight(1) + "X_") else prefix

  /** Whether the text can spell the prefix, literally or as the parse would put it back together.
    */
  private def mentions(source: String, prefix: String): Boolean =
    source.contains(prefix) || materialised(source).contains(prefix)

  /** What quoting can spell: `"` dropped, `\uXXXX` resolved (`\u005f` is `_`). An
    * over-approximation — it also joins pieces sconfig would keep apart — which only ever makes
    * [[prefixFor]] pick a longer prefix.
    */
  private def materialised(source: String): String = {
    val out = new StringBuilder
    var i   = 0
    while (i < source.length)
      if (source.charAt(i) == '"') i += 1
      else
        escapeAt(source, i) match {
          case Some((char, next)) =>
            val _ = out.append(char)
            i = next
          case None =>
            val _ = out.append(source.charAt(i))
            i += 1
        }
    out.toString
  }

  /** The character `\uXXXX` at `i` stands for, and where the escape ends; `None` for anything
    * else, including the escapes sconfig resolves to characters of their own.
    */
  private def escapeAt(source: String, i: Int): Option[(Char, Int)] =
    Option
      .when(
        i + 6 <= source.length && source.charAt(i) == '\\' && (source.charAt(i + 1) == 'u' || source.charAt(
          i + 1
        ) == 'U')
      ) {
        val hex = source.substring(i + 2, i + 6)
        Option.when(hex.forall(c => Character.digit(c, 16) >= 0))(hex).map { digits =>
          Integer.parseInt(digits, 16).toChar -> (i + 6)
        }
      }
      .flatten

  // ---- restoring -----------------------------------------------------------------------------

  private def restore(rendered: String, prefix: String, originals: Map[Int, String]): String =
    // Only the placeholders write the prefix here; anything else in the render is the user's text
    // spelled through quoting, which this pass cannot tell apart. Restoring nothing makes the
    // comments missing, and `HoconFormatter.commentsKept` refuses the file.
    if (occurrences(rendered, prefix) != PrefixPerPlaceholder * originals.size) rendered
    else {
      val guard =
        s"""$OptionalQuote${prefix}GUARD_(\\d+)$OptionalQuote[ \\t]*[:=][ \\t]*$OptionalQuote$GuardValue$OptionalQuote"""
      val field =
        s"""([ \\t]*)$OptionalQuote$prefix(\\d+)$OptionalQuote[ \\t]*[:=][ \\t]*$OptionalQuote$prefix(\\d+)$OptionalQuote"""
      val ours                     = originals.keySet
      def dropOurs(guard: Matcher) = Option.when(ours(guard.group(1).toInt))("")
      // As in IncludeMasking.unmask: own-line guards first, with a newline lent for the pass.
      val noOwnLine = replaceEachMatch("\n" + rendered, Pattern.compile(s"""\\n[ \\t]*$guard"""))(dropOurs).drop(1)
      val noGuards  = replaceEachMatch(noOwnLine, Pattern.compile(s""",?[ \\t]*$guard"""))(dropOurs)
      replaceEachMatch(noGuards, Pattern.compile(field)) { f =>
        Option
          .when(f.group(2) == f.group(3))(f.group(2).toInt)
          .flatMap(originals.get)
          .map(renderBlock(f.group(1), _))
      }
    }

  /** How often `needle` occurs in `text`. */
  private def occurrences(text: String, needle: String): Int = {
    @tailrec
    def count(from: Int, found: Int): Int = {
      val at = text.indexOf(needle, from)
      if (at < 0) found else count(at + needle.length, found + 1)
    }
    count(0, 0)
  }

  // Comments are stored trimmed and re-markered with `#`, the spelling formatting gives every
  // comment, so a restored block reads like one sconfig kept itself.
  private def renderBlock(indent: String, block: String): String =
    block
      .split('\n')
      .map { line =>
        val body = line.trim.stripPrefix("#").stripPrefix("//").trim
        if (body.isEmpty) s"$indent#" else s"$indent# $body"
      }
      .mkString("\n")

  private val OptionalQuote = """["]?"""
  private val GuardValue    = "g"

  private def placeholderFor(prefix: String, index: Int): String =
    s"""$prefix$index : "$prefix$index", ${prefix}GUARD_$index : "$GuardValue""""

  private def replaceEachMatch(text: String, pattern: Pattern)(
      replacement: Matcher => Option[String]
  ): String = {
    val matcher    = pattern.matcher(text)
    val out        = new StringBuilder
    var copiedUpTo = 0
    var from       = 0
    while (matcher.find(from))
      replacement(matcher) match {
        case Some(next) =>
          val _ = out.append(text.substring(copiedUpTo, matcher.start)).append(next)
          copiedUpTo = matcher.end
          from = matcher.end
        case None => from = matcher.start + 1
      }
    out.append(text.substring(copiedUpTo)).toString
  }

  // ---- which comments -------------------------------------------------------------------------

  /** Each maximal run of comment-only lines that nothing follows but the end of the object or file,
    * as the span of source it covers and the text. With the option on, every other block attaches
    * to the field below it.
    */
  private def blocksOf(source: String): List[(Int, Int, String)] = {
    val lines  = lineTable(source)
    val blocks = List.newBuilder[(Int, Int, String)]
    var i      = 0
    while (i < lines.length)
      if (maskable(lines(i))) {
        val first = i
        while (i < lines.length && maskable(lines(i))) i += 1
        val start = lines(first).start
        val end   = lines(i - 1).end
        blocks += ((start, end, source.substring(start, end)))
      } else i += 1
    blocks.result()
  }

  /** A comment line sconfig has no field to attach to: no array or parenthesis encloses it, and the next content line closes the object or there is none.
    */
  private def maskable(line: LineInfo): Boolean =
    line.kind == LineKind.CommentOnly && line.inFieldCtx &&
      line.next.forall(_.closeBrace)

  // ---- one line at a time ---------------------------------------------------------------------

  private enum LineKind derives CanEqual { case Blank, CommentOnly, Content }

  /** One line of the text: `start until end` is its content without the newline. A comment-only
    * line is one whose first character opens a comment, not one inside a string. `inFieldCtx` says no array or parenthesis
    * encloses it, so a placeholder field can stand there. `next` is the first content line below.
    */
  final private case class LineInfo(
      start: Int,
      end: Int,
      kind: LineKind,
      inFieldCtx: Boolean,
      closeBrace: Boolean,
      next: Option[LineInfo]
  )

  private def lineTable(source: String): Vector[LineInfo] = {
    val spans = HoconText.spans(source)
    val stack = mutable.ArrayBuffer.empty[Char]
    val rows  = mutable.ArrayBuffer.empty[LineInfo]

    var i         = 0
    var lineStart = 0
    var spanIdx   = 0
    while (i <= source.length) {
      val atEnd  = i == source.length
      val atLine = atEnd || source.charAt(i) == '\n'
      if (atLine) {
        // A line beginning where the text ends is not a line at all.
        if (!(atEnd && lineStart == source.length))
          rows += classify(source, spans, lineStart, i, stack.forall(_ == '{'))
        if (!atEnd) { i += 1; lineStart = i }
        else i += 1
      } else {
        while (spanIdx < spans.length && spans(spanIdx).end <= i) spanIdx += 1
        val inSpan = spanIdx < spans.length && spans(spanIdx).start <= i
        if (!inSpan)
          source.charAt(i) match {
            case '(' | '[' | '{' => stack += source.charAt(i)
            case ')' | ']' | '}' =>
              val _ = if (stack.nonEmpty) stack.remove(stack.length - 1): Unit
            case _ => ()
          }
        i += 1
      }
    }

    // Linked backwards, so each line knows the next content line.
    val linked                        = new Array[LineInfo](rows.length)
    var nextContent: Option[LineInfo] = None
    var idx                           = rows.length - 1
    while (idx >= 0) {
      linked(idx) = rows(idx).copy(next = nextContent)
      if (rows(idx).kind == LineKind.Content) nextContent = Some(linked(idx))
      idx -= 1
    }
    linked.toVector
  }

  private def classify(
      source: String,
      spans: Vector[HoconText.Span],
      start: Int,
      end: Int,
      inFieldCtx: Boolean
  ): LineInfo = {
    var p     = start
    var first = -1
    while (p < end && first < 0) {
      val c = source.charAt(p)
      if (c == ' ' || c == '\t' || c == '\r') p += 1 else first = p
    }
    val kind =
      if (first < 0) LineKind.Blank
      else if (spanAt(spans, first).exists(span => span.kind == HoconText.Kind.Comment && span.start == first))
        LineKind.CommentOnly
      else LineKind.Content
    LineInfo(
      start,
      end,
      kind,
      inFieldCtx = inFieldCtx,
      closeBrace = first >= 0 && HoconText.isCode(spans, first) && source.charAt(first) == '}',
      next = None
    )
  }

  private def spanAt(spans: Vector[HoconText.Span], position: Int): Option[HoconText.Span] = {
    @tailrec
    def search(low: Int, high: Int): Option[HoconText.Span] =
      if (low > high) None
      else {
        val middle = (low + high) >>> 1
        val span   = spans(middle)
        if (span.end <= position) search(middle + 1, high)
        else if (span.start > position) search(low, middle - 1)
        else Some(span)
      }
    search(0, spans.size - 1)
  }
}
