package eu.ww86.hoconfmt

import scala.annotation.tailrec

/** Which parts of a HOCON text are code, strings and comments, found in one left-to-right pass.
  *
  * Enough lexing to answer two questions: whether an `include` is code, and which comments a text
  * holds. Deciding the first by counting quotes from the start of the file went wrong on a quote
  * inside a comment, and made every `include` cost a scan of everything before it.
  */
private[hoconfmt] object HoconText {

  enum Kind derives CanEqual { case Quoted, Comment }

  /** `text.substring(start, end)`; code is whatever no span covers. */
  final case class Span(kind: Kind, start: Int, end: Int)

  // Ten times the deepest explicit nesting in the 1,257-file corpus; see limitations.md.
  val MaxNestingDepth = 80

  // Ten times the longest dotted key path in the same corpus (11 segments), rounded up. The
  // written-line half of the path bound: the duplicate report's work on a dotted statement grows with
  // the cube of its path's segments, so a single-line `a.a.a. ... = 1` spent 24 seconds in it at
  // 2,000 segments; see limitations.md.
  val MaxPathSegments = 120

  // Nesting and array elements extend a written path in the report. Retain the individual
  // flattened cap alongside the total-work budget; a per-path cap alone left dense files
  // unbounded (160 KB spent 12-13 seconds in the report).
  val MaxFlattenedPathSegments = 360

  // Prefix creation and hashing cost the report the sum of squared definition lengths. The
  // 30 measured dense shapes fit this proxy better than cubes (R² .993 vs .982). Ten times
  // the corpus maximum of 26,725, rounded up; see limitations.md for the calibration and times.
  val MaxPathWork = 300000L

  /** Stop before the parser or include passes can spend resources on unbounded nesting, and
    * before the duplicate report can spend them on an unbounded dotted key path, written on one
    * line, re-created by nesting, or summed over a file of many definitions.
    */
  def checkNesting(text: String): Either[Refusal, Unit] = {
    // A key path runs from where a field can begin — the file, `{`, `,` or a newline — to the
    // `=`, `:` or `{` of its value. sconfig splits it into elements at unquoted dots and joins
    // adjacent quoted and unquoted pieces into one element (`"a"."b"` and `"a""b"` differ),
    // so a quoted piece begins a new segment only where an element can begin. Whitespace ends
    // nothing but the line: `a . b` is two elements, `a b` one, and `a.\nb` does not parse.
    // The state is three flags, the count of the path last written and still without its value
    // (`pending`), and the enclosing key paths as a stack with their running sum, over the same
    // walk the depth check does: a `{` makes the path just written the enclosing one for
    // everything inside, a `[` passes the pending path to the
    // objects of its elements and costs one segment for the element itself — every definition
    // inside an array element carries one `Index` segment per enclosing array level in the
    // report's walk (`DuplicateReport.walk`, the array case), so the flattened count here has
    // to count them too — and a `}` or `]` takes the innermost back out (an unbalanced closer
    // takes nothing out, like the depth it never opened). The separator does not spend the
    // written path: it moves it to `pending`, and the value's spelling decides when it is
    // used — across blanks, a newline, a comment or a substitution the value may still begin
    // with `{` or `[` (`a.b.c =\n{ ... }` parses, and `k = ${x} { ... }` is an object
    // concatenation), while a word or a quote is a value that is not an object and takes the
    // count away; a `,` ends the statement. A closer restores the field's pending path so a
    // later concatenation piece inherits it too. A substitution is skipped whole and can stand
    // in no key path.
    //
    // Every statement that writes a path also defines it, separator or not: `a.b = { ... }` and
    // `a.b { ... }` both leave the definitions of `a` and `a.b` behind, so the work is counted
    // where the statement is spent — at the separator, and at the `{` or `[` a written path
    // stands before (an include stands before no separator and defines nothing, so it counts
    // nothing). The work of one statement is the sum of the squared flattened lengths of the
    // definitions it writes, in closed form, and the running total is refused past the budget:
    // the report builds and hashes every prefix of every definition's path. Its reverse index
    // avoids multiplying that work by the number of definitions sharing a path.
    @tailrec
    def scan(
        index: Int,
        depth: Int,
        segments: Int,
        pending: Int,
        enclosing: List[(Int, Int)],
        flat: Int,
        work: Long,
        atKeyStart: Boolean,
        afterDot: Boolean
    ): Either[Refusal, Unit] =
      if (segments > MaxPathSegments) Left(Refusal.PathTooDeep(writtenLimitReason))
      else if (flat + segments > MaxFlattenedPathSegments)
        Left(Refusal.PathTooDeep(flattenedLimitReason))
      else if (work > MaxPathWork) Left(Refusal.PathTooDeep(workBudgetReason))
      else if (index >= text.length) Right(())
      else if (text.startsWith("\"\"\"", index))
        scan(
          endOfTripleQuoted(text, index + 3),
          depth,
          beginElement(segments, atKeyStart, afterDot),
          pending = 0,
          enclosing,
          flat,
          work,
          atKeyStart = false,
          afterDot = false
        )
      else if (text.charAt(index) == '"')
        scan(
          endOfQuoted(text, index + 1),
          depth,
          beginElement(segments, atKeyStart, afterDot),
          pending = 0,
          enclosing,
          flat,
          work,
          atKeyStart = false,
          afterDot = false
        )
      else if (text.charAt(index) == '#' || text.startsWith("//", index))
        scan(endOfLine(text, index), depth, segments, pending, enclosing, flat, work, atKeyStart, afterDot)
      // `${x}` and `${?x}` open no object: a file that nests exactly to the limit may use them.
      else if (text.startsWith("${", index))
        scan(
          endOfSubstitution(text, index + 2),
          depth,
          segments = 0,
          pending,
          enclosing,
          flat,
          work,
          atKeyStart = false,
          afterDot = false
        )
      else {
        text.charAt(index) match {
          case '{' =>
            if (depth == MaxNestingDepth) Left(Refusal.NestingTooDeep(MaxNestingDepth))
            else {
              val enclosingPath = if (segments > 0) segments else pending
              scan(
                index + 1,
                depth + 1,
                segments = 0,
                pending = 0,
                (enclosingPath, 0) :: enclosing,
                flat + enclosingPath,
                work + statementWork(flat, segments),
                atKeyStart = true,
                afterDot = false
              )
            }
          case '[' =>
            if (depth == MaxNestingDepth) Left(Refusal.NestingTooDeep(MaxNestingDepth))
            else {
              val enclosingPath = if (segments > 0) segments else pending
              scan(
                index + 1,
                depth + 1,
                segments = 0,
                pending = 0,
                (enclosingPath, 1) :: enclosing,
                flat + enclosingPath + 1,
                work + statementWork(flat, segments),
                atKeyStart = false,
                afterDot = false
              )
            }
          case '}' | ']' =>
            enclosing match {
              case (path, element) :: rest =>
                scan(
                  index + 1,
                  math.max(0, depth - 1),
                  segments = 0,
                  pending = path,
                  rest,
                  flat - path - element,
                  work,
                  atKeyStart = false,
                  afterDot = false
                )
              case Nil =>
                scan(
                  index + 1,
                  math.max(0, depth - 1),
                  segments = 0,
                  pending = 0,
                  enclosing,
                  flat,
                  work,
                  atKeyStart = false,
                  afterDot = false
                )
            }
          // The value follows: the path just written, `+=` included, is whole, and stays pending
          // until the value says whether it is an object. A second path cannot be written before
          // the value, so a separator with a count in hand starts one; `+=` arrives here twice
          // and the `=` of it finds nothing new to move.
          case '=' | ':' | '+' =>
            scan(
              index + 1,
              depth,
              segments = 0,
              pending = if (segments > 0) segments else pending,
              enclosing,
              flat,
              work + statementWork(flat, segments),
              atKeyStart = false,
              afterDot = false
            )
          case ',' =>
            scan(
              index + 1,
              depth,
              segments = 0,
              pending = 0,
              enclosing,
              flat,
              work,
              atKeyStart = true,
              afterDot = false
            )
          case '\n' =>
            scan(index + 1, depth, segments = 0, pending, enclosing, flat, work, atKeyStart = true, afterDot = false)
          // A dot ends an element; the next piece or word begins one. A leading dot belongs to
          // no path and changes nothing.
          case '.' =>
            scan(
              index + 1,
              depth,
              segments,
              pending,
              enclosing,
              flat,
              work,
              atKeyStart = false,
              afterDot = segments > 0
            )
          // Whitespace keeps the run: `a . b` and `a b` are one path expression either way.
          case ' ' | '\t' | '\r' | '\u000C' =>
            scan(index + 1, depth, segments, pending, enclosing, flat, work, atKeyStart, afterDot)
          case _ =>
            // A word is a key, which takes the count of the path it begins, or the start of a
            // value that is not an object, which takes the pending count away.
            if (atKeyStart || afterDot)
              scan(
                index + 1,
                depth,
                segments + 1,
                pending = 0,
                enclosing,
                flat,
                work,
                atKeyStart = false,
                afterDot = false
              )
            else
              scan(index + 1, depth, segments, pending = 0, enclosing, flat, work, atKeyStart = false, afterDot = false)
        }
      }

    // A quoted piece begins a segment where an element can begin: at the start of a key path or
    // right after a dot. Beside another piece it joins that element.
    def beginElement(segments: Int, atKeyStart: Boolean, afterDot: Boolean): Int =
      if (atKeyStart || afterDot) segments + 1 else segments

    scan(0, 0, segments = 0, pending = 0, enclosing = Nil, flat = 0, work = 0L, atKeyStart = true, afterDot = false)
  }

  /** The work of one statement: `w` written segments at enclosing length `e` define paths of
    * `e+1` .. `e+w` segments (every prefix is a definition the dotted spelling leaves behind),
    * and the report builds and hashes every prefix of each definition's path, so its cost is
    * the sum of the squared lengths. Closed form, `Long` where a `Long` is needed: a statement
    * under the other limits contributes at most ~21 million, and the total is refused long
    * before it could overflow.
    */
  private def statementWork(e: Int, w: Int): Long = {
    def squareSum(k: Long): Long = k * (k + 1) * (2 * k + 1) / 6
    squareSum(e.toLong + w) - squareSum(e.toLong)
  }

  private def writtenLimitReason   = s"input dotted key paths exceed the maximum of $MaxPathSegments segments"
  private def flattenedLimitReason = s"input dotted key paths exceed the maximum of $MaxFlattenedPathSegments segments"
  private def workBudgetReason     =
    s"the key paths' total squared length exceeds the duplicate report's budget of $MaxPathWork"

  def spans(text: String): Vector[Span] = {
    @tailrec
    def scan(i: Int, found: Vector[Span]): Vector[Span] =
      if (i >= text.length) found
      else if (text.startsWith("\"\"\"", i)) {
        val end = endOfTripleQuoted(text, i + 3)
        scan(end, found :+ Span(Kind.Quoted, i, end))
      } else if (text.charAt(i) == '"') {
        val end = endOfQuoted(text, i + 1)
        scan(end, found :+ Span(Kind.Quoted, i, end))
      } else if (text.charAt(i) == '#' || text.startsWith("//", i)) {
        val end = endOfLine(text, i)
        scan(end, found :+ Span(Kind.Comment, i, end))
      } else scan(i + 1, found)
    scan(0, Vector.empty)
  }

  /** Whether `position` falls in code rather than in a string or a comment. */
  def isCode(spans: Vector[Span], position: Int): Boolean = {
    // The spans are ordered and disjoint: find the last one starting at or before `position`.
    @tailrec
    def search(low: Int, high: Int): Boolean =
      if (low > high) high < 0 || spans(high).end <= position
      else {
        val middle = (low + high) >>> 1
        if (spans(middle).start <= position) search(middle + 1, high) else search(low, middle - 1)
      }
    search(0, spans.size - 1)
  }

  /** Each comment's text without its marker or surrounding whitespace, in order. sconfig rewrites
    * `//` as `#` and trims, so this is the form that survives formatting.
    */
  def comments(text: String): List[String] =
    spans(text).toList.collect { case Span(Kind.Comment, start, end) =>
      val body = text.substring(start, end)
      body.stripPrefix("#").stripPrefix("//").trim
    }

  // A triple-quoted string ends at the last quote of the first run of three or more.
  private def endOfTripleQuoted(text: String, from: Int): Int = {
    val close = text.indexOf("\"\"\"", from)
    if (close < 0) text.length
    else {
      val afterRun = text.indexWhere(_ != '"', close)
      if (afterRun < 0) text.length else afterRun
    }
  }

  // An unterminated string stops at the end of its line, as sconfig's does.
  @tailrec
  private def endOfQuoted(text: String, i: Int): Int =
    if (i >= text.length) text.length
    else
      text.charAt(i) match {
        case '\\' => endOfQuoted(text, i + 2)
        case '"'  => i + 1
        case '\n' => i
        case _    => endOfQuoted(text, i + 1)
      }

  // The path of a substitution may hold quoted keys, so a `}` inside quotes does not close it.
  @tailrec
  private def endOfSubstitution(text: String, i: Int): Int =
    if (i >= text.length) text.length
    else
      text.charAt(i) match {
        case '"'  => endOfSubstitution(text, endOfQuoted(text, i + 1))
        case '}'  => i + 1
        case '\n' => i
        case _    => endOfSubstitution(text, i + 1)
      }

  private def endOfLine(text: String, from: Int): Int = {
    val newline = text.indexOf('\n', from)
    if (newline < 0) text.length else newline
  }
}
