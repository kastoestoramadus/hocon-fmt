package ww86.hocon_fmt

import java.util.regex.{Matcher, Pattern}

import scala.annotation.tailrec
import scala.collection.mutable

/** PROBE, not for merging: carries comments sconfig would drop — and optionally blank-line runs —
  * across the parse-render round trip, the way [[IncludeMasking]] carries includes. Each comment
  * block sconfig would lose (one a blank line follows, one no field follows, a whole comment-only
  * file) becomes a placeholder field before parsing and is put back after rendering.
  *
  * Runs after [[IncludeMasking]]: the text it sees has includes already replaced, so a comment
  * above an include placeholder survives the same way. `IncludeOrder.leavesOf` filters these
  * placeholder and guard keys, since the source it reads for comparison never has them.
  *
  * Mode comes from the `hocon_fmt.probe` system property: `off`, `comments` (the default) or
  * `blanks` (comments, plus every blank-line run kept as one blank line). Blank placeholders go
  * only where a field can stand: not inside arrays, parentheses or multi-line strings.
  *
  * The regexes must hold on RE2 and ES2015: no lookaround, no backreferences, no multiline `^`.
  */
@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
private[hocon_fmt] object ProbeMasking {

  val ModeProperty = "hocon_fmt.probe"
  private def mode: String = Option(sys.props.get(ModeProperty)).flatten.getOrElse("comments")

  def mask(source: String): IncludeMasking.Masked = {
    val commented = if (mode == "off") IncludeMasking.Masked(source, Map.empty) else maskComments(source)
    if (mode == "blanks")
      maskLines(
        commented.text,
        commented.originals,
        BlankPrefix,
        line => line.kind == LineKind.Blank && line.startsInCode && line.inFieldCtx
      )
    else commented
  }

  /** Puts the restored comments and blank lines back and drops the guards, own-line guards first,
    * as [[IncludeMasking.unmask]] does.
    */
  def unmask(rendered: String, originals: Map[Int, String]): String = {
    val ours                     = originals.keySet
    def dropOurs(guard: Matcher) = Option.when(ours(guard.group(1).toInt))("")
    def withoutGuards(text: String, ownLine: Pattern, inline: Pattern): String = {
      val noOwnLine = replaceEachMatch("\n" + text, ownLine)(dropOurs).drop(1)
      replaceEachMatch(noOwnLine, inline)(dropOurs)
    }
    val step1 = withoutGuards(rendered, CommentGuardOwnLine, CommentGuardInline)
    val step2 = withoutGuards(step1, BlankGuardOwnLine, BlankGuardInline)
    val step3 = replaceEachMatch(step2, CommentField)(restoreComment(originals))
    val step4  = replaceEachMatch(step3, BlankField)(restoreBlank(originals))
    // Blank placeholders the renderer stacked adjacently come back as a run of blank lines, which
    // the next pass would mask as one run: collapse it here, or the formatter is not a fixed point.
    // Only these restores can produce blank lines; sconfig renders strings single-line.
    if (mode == "blanks") BlankRunCollapse.matcher(step4).replaceAll("\n\n") else step4
  }
  private def restoreComment(originals: Map[Int, String])(field: Matcher): Option[String] =
    Option
      .when(field.group(2) == field.group(3))(field.group(2).toInt)
      .flatMap(originals.get)
      .map(renderBlock(field.group(1), _))

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

  private def restoreBlank(originals: Map[Int, String])(field: Matcher): Option[String] =
    Option
      .when(field.group(2) == field.group(3))(field.group(2).toInt)
      .flatMap(originals.get)
      .map(_ => "")

  // ---- masking passes -------------------------------------------------------------------------

  private def maskComments(source: String): IncludeMasking.Masked =
    maskLines(
      source,
      Map.empty,
      CommentPrefix,
      line => line.kind == LineKind.CommentOnly && line.inFieldCtx && droppedBySconfig(line)
    )

  /** A comment block is dropped when a blank line comes before the next content, when the next
    * content closes its object, or when nothing follows it at all. `gapBefore` is the comment
    * line's own flag: whether blanks sit between it and that next content.
    */
  private def droppedBySconfig(line: LineInfo): Boolean =
    line.next.exists(n => line.gapBefore || n.closeBrace) || line.next.isEmpty

  /** Replaces every maximal run of maskable lines with a placeholder field, numbered from above
    * `prior`'s indices, the original text kept for the restore.
    */
  private def maskLines(
      source: String,
      prior: Map[Int, String],
      prefix: String,
      maskable: LineInfo => Boolean
  ): IncludeMasking.Masked = {
    val lines = lineTable(source)
    val cuts  = mutable.ArrayBuffer.empty[(Int, Int, String)]

    var i = 0
    while (i < lines.length) {
      if (maskable(lines(i))) {
        val startLine = i
        val text      = new StringBuilder
        while (i < lines.length && maskable(lines(i))) {
          if (text.nonEmpty) text.append('\n')
          text.append(source.substring(lines(i).start, lines(i).end))
          i += 1
        }
        cuts += ((lines(startLine).start, lines(i - 1).end, text.result()))
      } else i += 1
    }

    val masked    = new StringBuilder
    val originals = Map.newBuilder[Int, String]
    var copied    = 0
    var index     = prior.keys.foldLeft(0)((acc, k) => math.max(acc, k + 1))
    cuts.foreach { case (start, end, original) =>
      masked.append(source.substring(copied, start))
      masked.append(placeholderFor(index, prefix))
      originals += index -> original
      index += 1
      copied = end
    }
    masked.append(source.substring(copied))
    IncludeMasking.Masked(masked.result(), prior ++ originals.result())
  }

  // ---- one line at a time ---------------------------------------------------------------------

  enum LineKind derives CanEqual { case Blank, CommentOnly, Content }

  /** One line of the text: `start until end` is its content without the newline. `startsInCode`
    * says the line does not open inside a string. `inFieldCtx` says no array or parenthesis
    * encloses it, so a placeholder field can stand there. `next` is the first content line to the
    * right, and `gapBefore` whether a blank line sits between them.
    */
  final case class LineInfo(
      start: Int,
      end: Int,
      kind: LineKind,
      startsInCode: Boolean,
      inFieldCtx: Boolean,
      closeBrace: Boolean,
      next: Option[LineInfo],
      gapBefore: Boolean
  )

  // PROBE debug aid, not for merging.
  def debugLines(source: String): String =
    lineTable(source).map(l => s"[${l.start},${l.end}) kind=${l.kind} ctx=${l.inFieldCtx} close=${l.closeBrace} next=${l.next.map(_.start)} gap=${l.gapBefore}").mkString("\n")

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
        // A line beginning where the text ends is not a line at all: without this, the empty
        // text after a final newline would count as one more blank line.
        if (!(atEnd && lineStart == source.length))
          rows += classify(source, spans, lineStart, i, stack.forall(_ == '{'))
        if (!atEnd) { i += 1; lineStart = i } else i += 1
      } else {
        while (spanIdx < spans.length && spans(spanIdx).end <= i) spanIdx += 1
        val inSpan = spanIdx < spans.length && spans(spanIdx).start <= i
        if (!inSpan)
          source.charAt(i) match {
            case '(' | '[' | '{' => stack += source.charAt(i)
            case ')' | ']' | '}' =>
              val _ = if (stack.nonEmpty) stack.remove(stack.length - 1): Unit
            case _               => ()
          }
        i += 1
      }
    }

    // Linked backwards, so each line knows the next content line and whether blanks separate them.
    val linked = new Array[LineInfo](rows.length)
    var nextContent: Option[LineInfo] = None
    var gap = false
    var idx = rows.length - 1
    while (idx >= 0) {
      val line = rows(idx)
      linked(idx) = line.copy(next = nextContent, gapBefore = gap)
      if (line.kind == LineKind.Content) { nextContent = Some(linked(idx)); gap = false }
      else if (line.kind == LineKind.Blank) gap = true
      idx -= 1
    }
    linked.toVector
  }

  private def classify(source: String, spans: Vector[HoconText.Span], start: Int, end: Int, inFieldCtx: Boolean): LineInfo = {
    var p     = start
    var first = -1
    while (p < end && first < 0) {
      val c = source.charAt(p)
      if (c == ' ' || c == '\t' || c == '\r') p += 1 else first = p
    }
    val at = if (first >= 0) first else start
    val kind =
      if (first < 0) LineKind.Blank
      else if (spanAt(spans, first).exists(_.kind == HoconText.Kind.Comment)) LineKind.CommentOnly
      else LineKind.Content
    LineInfo(
      start,
      end,
      kind,
      startsInCode = spanAt(spans, at).isEmpty,
      inFieldCtx = inFieldCtx,
      closeBrace = first >= 0 && HoconText.isCode(spans, first) && source.charAt(first) == '}',
      next = None,
      gapBefore = false
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

  // ---- placeholders and their patterns --------------------------------------------------------

  val CommentPrefix = "__COMMENT_"
  val BlankPrefix   = "__BLANK_"

  private def placeholderFor(index: Int, prefix: String): String =
    s"""$prefix$index : "$prefix$index", ${prefix}GUARD_$index : "g""""

  private val OptionalQuote = """["]?"""

  private def fieldPattern(prefix: String): Pattern = Pattern.compile(
    s"""([ \\t]*)$OptionalQuote$prefix(\\d+)$OptionalQuote[ \\t]*:[ \\t]*""" +
      s"""$OptionalQuote$prefix(\\d+)$OptionalQuote"""
  )

  private def guardPattern(prefix: String): String =
    s"""$OptionalQuote$prefix(\\d+)$OptionalQuote[ \\t]*:[ \\t]*${OptionalQuote}g$OptionalQuote"""

  private val CommentField = fieldPattern(CommentPrefix)
  private val BlankField   = fieldPattern(BlankPrefix)

  // The renderer may quote the guard value or not, and may put the guard on the placeholder's
  // line or one below; both spellings have to match, own-line guards consumed before inline ones.
  private val CommentGuardOwnLine = Pattern.compile(s"""\\n[ \\t]*${guardPattern(s"${CommentPrefix}GUARD_")}""")
  private val CommentGuardInline  = Pattern.compile(s""",?[ \\t]*${guardPattern(s"${CommentPrefix}GUARD_")}""")
  private val BlankGuardOwnLine   = Pattern.compile(s"""\\n[ \\t]*${guardPattern(s"${BlankPrefix}GUARD_")}""")
  private val BlankGuardInline    = Pattern.compile(s""",?[ \\t]*${guardPattern(s"${BlankPrefix}GUARD_")}""")
  private val BlankRunCollapse    = Pattern.compile("\\n{3,}")

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
}
