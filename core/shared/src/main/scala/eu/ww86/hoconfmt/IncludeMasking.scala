package eu.ww86.hoconfmt

import java.util.regex.{Matcher, Pattern}

import org.ekrich.config.ConfigObject

import scala.annotation.tailrec

/** Carries `include` directives across a parse-render round trip.
  *
  * sconfig resolves an include while parsing and keeps nothing to render afterwards, so the
  * directive would simply vanish. Each whole statement is swapped for a placeholder field before
  * parsing and swapped back once the rendered text comes out.
  *
  * Swapping the whole statement, rather than the keyword alone, is what lets an include share a
  * line with other content. The scheme this replaced commented out the rest of the line, which
  * swallowed closing braces and any entries following the include.
  *
  * A character scanner on the hot path of every format, written with indices and loops; the
  * suppression is for this object only, not a precedent.
  */
@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While", "org.wartremover.warts.Return"))
private[hoconfmt] object IncludeMasking {

  /** Masked text, plus the statements it replaced keyed by their placeholder index. */
  case class Masked(text: String, originals: Map[Int, String])

  /** With `onOwnLines`, each placeholder is also put on a line of its own, so that sconfig's
    * origin lines tell where it stood among the fields around it: [[IncludeOrder]] reads the
    * source that way. The text is for reading only, never rendered; a comma after the statement
    * stays on the placeholder's line, since a line may not begin with one.
    */
  def mask(source: String, onOwnLines: Boolean = false): Masked =
    maskWithPrefix(source, onOwnLines, PlaceholderPrefix)

  private def maskWithPrefix(source: String, onOwnLines: Boolean, prefix: String): Masked = {
    val masked       = new StringBuilder
    val originals    = Map.newBuilder[Int, String]
    val keywords     = IncludeKeyword.matcher(source)
    lazy val nonCode = HoconText.spans(source) // only a file that mentions include pays for it
    var copiedUpTo   = 0
    var nextIndex    = 0

    while (keywords.find())
      if (keywords.start >= copiedUpTo && HoconText.isCode(nonCode, keywords.start))
        targetAfterKeyword(source, keywords.end).foreach { target =>
          masked.append(source.substring(copiedUpTo, keywords.start))
          if (onOwnLines) masked.append('\n')
          masked.append(placeholderFor(nextIndex, prefix))
          originals += nextIndex -> s"include ${target.text}"
          nextIndex += 1
          copiedUpTo = target.endIndex
          if (onOwnLines) {
            val comma = skipBlanks(source, copiedUpTo)
            if (comma < source.length && source.charAt(comma) == ',') {
              masked.append(source.substring(copiedUpTo, comma + 1))
              copiedUpTo = comma + 1
            }
            masked.append('\n')
          }
        }

    masked.append(source.substring(copiedUpTo))
    Masked(masked.result(), originals.result())
  }

  def unmask(rendered: String, originals: Map[Int, String]): String = {
    val restored = replaceEachMatch(rendered, PlaceholderField) { field =>
      // Compared as text, as a backreference would: `__INCLUDE_01` is not placeholder 1.
      val sameIndex = field.group(1) == field.group(2)
      // An index we never handed out belongs to the user's own text: leave it untouched.
      Option.when(sameIndex)(indexOf(field)).flatten.flatMap(originals.get)
    }
    // Own-line first: it consumes the newline and indentation, which the inline pattern leaves
    // behind. The other order turns every guard on its own line into a blank one. sconfig may
    // render a guard on the very first line, with no newline before it, so one is lent for the
    // pass; a multiline `^` would do instead, but Scala.js supports it only from ES2018.
    val ours                     = originals.keySet
    def dropOurs(guard: Matcher) = Option.when(indexOf(guard).exists(ours))("")
    val withoutOwnLineGuards     = replaceEachMatch("\n" + restored, GuardOnItsOwnLine)(dropOurs).drop(1)
    replaceEachMatch(withoutOwnLineGuards, GuardInline)(dropOurs)
  }

  /** A cheap source check also protects comments, which are outside the parsed value tree.
    * The underscore escape check is deliberately broad, including comments and triple quotes.
    */
  def collides(masked: Masked): Boolean =
    masked.originals.nonEmpty && (occurrences(masked.text) != ours(masked) || masked.text.toLowerCase.contains(
      "\\u005f"
    ))

  /** Check field pairs, not independent allowed strings: a placeholder value under a user key is
    * still the user's value. Judged by [[PlaceholderTree]], as the site's comment placeholders are.
    */
  def collides(masked: Masked, parsed: ConfigObject): Boolean =
    masked.originals.nonEmpty && PlaceholderTree.collides(parsed, PlaceholderPrefix, generated(masked.originals))

  /** The key/value pairs this pass wrote, the only reserved names the tree may hold. */
  private def generated(originals: Map[Int, String]): Map[String, String] =
    originals.keysIterator.flatMap { index =>
      List(s"$PlaceholderPrefix$index" -> s"$PlaceholderPrefix$index", s"$GuardPrefix$index" -> GuardValue)
    }.toMap

  /** An identical user field can overwrite a generated field before we see the tree. Parse again
    * with generated names absent from the first rendering: user reserved names then remain visible,
    * even when spelled as concatenated tokens. No generated reserved names are allowed in this tree.
    */
  def collisionProbe(source: String, rendered: String): Masked =
    maskWithPrefix(source, onOwnLines = false, s"$ProbePrefix${firstUnusedProbeIndex(source, rendered)}_")

  /** The smallest index whose prefix occurs in neither text, found by one scan per text over the
    * whole `__HOCON_MASK_<digits>_` family. Asking `contains` per candidate index rescanned both
    * texts once per index, so a comment listing the first thirty thousand indices made formatting
    * quadratic in the texts' length.
    */
  private def firstUnusedProbeIndex(source: String, rendered: String): Int = {
    val used = probeFamilyIndices(source) ++ probeFamilyIndices(rendered)
    @tailrec
    def firstFree(index: Int): Int = if (used(index.toString)) firstFree(index + 1) else index
    firstFree(0)
  }

  /** Scans one text for the family, resuming one character past each match as the other scans in
    * this object do, so a prefix starting inside another match is still found. The digit string is
    * kept as written: `__HOCON_MASK_01_` spells index 1 but does not contain `__HOCON_MASK_1_`, and
    * the two differ to the `contains` this replaces.
    */
  private def probeFamilyIndices(text: String): Set[String] = {
    val matcher = ProbeFamily.matcher(text)
    @tailrec
    def collect(from: Int, found: Set[String]): Set[String] =
      if (!matcher.find(from)) found else collect(matcher.start + 1, found + matcher.group(1))
    collect(0, Set.empty)
  }

  def probeCollides(parsed: ConfigObject): Boolean = PlaceholderTree.collides(parsed, PlaceholderPrefix, Map.empty)

  /** Each index must have exactly one complete placeholder and guard, with no other reserved text.
    * A total alone cannot detect one index replacing another.
    */
  def collides(masked: Masked, rendered: String): Boolean = {
    def indices(pattern: Pattern, placeholder: Boolean): List[String] = {
      val matcher = pattern.matcher(rendered)
      @tailrec
      def collect(from: Int, found: List[String]): List[String] =
        if (!matcher.find(from)) found
        else {
          val index = matcher.group(1)
          val valid = !placeholder || index == matcher.group(2)
          collect(matcher.start + 1, if (valid) index :: found else found)
        }
      collect(0, Nil)
    }
    val expected = masked.originals.keys.toList.map(_.toString).sorted
    masked.originals.nonEmpty && (occurrences(rendered) != ours(masked) ||
      indices(PlaceholderField, placeholder = true).sorted != expected ||
      indices(GuardField, placeholder = false).sorted != expected)
  }

  private def ours(masked: Masked): Int = 3 * masked.originals.size

  private def occurrences(text: String): Int = {
    @tailrec
    def count(from: Int, found: Int): Int = {
      val at = text.indexOf(PlaceholderPrefix, from)
      if (at < 0) found else count(at + PlaceholderPrefix.length, found + 1)
    }
    count(0, 0)
  }

  /** The index a match names, if it fits an `Int`: a longer run of digits is the user's, since we
    * never hand out so many placeholders.
    */
  private def indexOf(matched: Matcher): Option[Int] =
    Option(matched.group(1)).flatMap(_.toIntOption)

  /** The statements whose placeholder sconfig did not render, in source order. It drops a field
    * the way it drops everything in an object that a later definition of the same key replaces.
    */
  def lost(rendered: String, originals: Map[Int, String]): List[String] = {
    val matcher = PlaceholderField.matcher(rendered)
    // Resumes one character past each match, as `replaceEachMatch` does, so a near miss cannot
    // hide a placeholder that starts inside it.
    @tailrec
    def present(from: Int, found: Set[Int]): Set[Int] =
      if (!matcher.find(from)) found
      else {
        val index = matcher.group(1)
        present(matcher.start + 1, if (index == matcher.group(2)) found ++ indexOf(matcher) else found)
      }
    (originals.keySet -- present(0, Set.empty)).toList.sorted.map(originals)
  }

  // ---- placeholders --------------------------------------------------------------------------
  //
  // The guard field exists because setSimplifyNestedObjects collapses a single-field object into
  // a dotted path: `o { X: v }` becomes `o.X: v`, which would move the placeholder out of its
  // object and leave nothing to restore. A second field keeps the object from collapsing.

  val PlaceholderPrefix = "__INCLUDE_"
  val GuardPrefix       = "__INCLUDE_GUARD_"
  val GuardValue        = "g"

  /** The family `collisionProbe` names its generated fields with; the regex is RE2- and
    * ES2015-safe, like every other pattern here.
    */
  private val ProbePrefix = "__HOCON_MASK_"
  private val ProbeFamily = Pattern.compile(ProbePrefix + """(\d+)_""")

  private def placeholderFor(index: Int, prefix: String): String =
    s"""$prefix$index : "$prefix$index", """ +
      s"""${prefix}GUARD_$index : "$GuardValue""""

  private val OptionalQuote = """["]?"""

  /** Built from the same constants the placeholders are written with, so the two cannot drift.
    *
    * The separator matches `:` and `=` because the renderer writes the token the asked-for
    * [[FormatOptions.separator]] names, and both spellings are our own placeholders. The value's
    * index is captured rather than backreferenced to the key's, because Scala Native's
    * `java.util.regex` is RE2-based and has no backreferences; `unmask` compares the two.
    */
  private val PlaceholderField = Pattern.compile(
    s"""$OptionalQuote$PlaceholderPrefix(\\d+)$OptionalQuote\\s*[:=]""" +
      s"""\\s*$OptionalQuote$PlaceholderPrefix(\\d+)$OptionalQuote"""
  )

  // The renderer may or may not quote the guard value, so both spellings have to match.
  private val guardField =
    s"""$OptionalQuote$GuardPrefix(\\d+)$OptionalQuote[ \\t]*[:=][ \\t]*$OptionalQuote$GuardValue$OptionalQuote"""
  private val GuardField        = Pattern.compile(guardField)
  private val GuardOnItsOwnLine = Pattern.compile(s"""\\n[ \\t]*$guardField""")
  private val GuardInline       = Pattern.compile(s""",?[ \\t]*$guardField""")

  /** Rewrites every match `replacement` accepts, leaving the rest verbatim.
    *
    * A rejected match is not consumed: the search resumes one character past its start, as a
    * regex engine does when a backreference fails there. Consuming it would let a near miss
    * swallow the key of a real placeholder that follows it.
    */
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

  // ---- locating an include statement ---------------------------------------------------------

  private val IncludeKeyword  = Pattern.compile("""\binclude""")
  private val TargetFunctions = List("required", "file", "url", "classpath")

  /** The target text of an include whose keyword ends at `afterKeyword`, and where the statement
    * ends. `None` when what follows is not a target at all, which is how `include_path` and the
    * word "include" in prose are left alone.
    */
  private case class Target(text: String, endIndex: Int)

  private def targetAfterKeyword(source: String, afterKeyword: Int): Option[Target] = {
    val start = skipWhitespace(source, afterKeyword)
    if (start >= source.length) None
    else if (source.charAt(start) == '"')
      endOfQuotedString(source, start).map(end => Target(source.substring(start, end), end))
    else
      for {
        function <- TargetFunctions.find(source.startsWith(_, start))
        openParen = skipWhitespace(source, start + function.length)
        if openParen < source.length && source.charAt(openParen) == '('
        end <- endOfParenGroup(source, openParen)
      } yield Target(function + source.substring(openParen, end), end)
  }

  private def skipBlanks(source: String, from: Int): Int = {
    var i = from
    while (i < source.length && (source.charAt(i) == ' ' || source.charAt(i) == '\t')) i += 1
    i
  }

  private def skipWhitespace(source: String, from: Int): Int = {
    var i = from
    while (i < source.length && Character.isWhitespace(source.charAt(i))) i += 1
    i
  }

  /** One past the closing quote, or `None` when the literal is unterminated. */
  private def endOfQuotedString(source: String, start: Int): Option[Int] = {
    var i = start + 1
    while (i < source.length)
      source.charAt(i) match {
        case '\\' => i += 2
        case '"'  => return Some(i + 1)
        case _    => i += 1
      }
    None
  }

  /** One past the matching close paren, or `None` when the group is unbalanced. */
  private def endOfParenGroup(source: String, start: Int): Option[Int] = {
    var i     = start
    var depth = 0
    while (i < source.length)
      source.charAt(i) match {
        case '"' =>
          endOfQuotedString(source, i) match {
            case Some(end) => i = end
            case None      => return None
          }
        case '(' => depth += 1; i += 1
        case ')' =>
          depth -= 1
          if (depth == 0) return Some(i + 1) else i += 1
        case _ => i += 1
      }
    None
  }
}
