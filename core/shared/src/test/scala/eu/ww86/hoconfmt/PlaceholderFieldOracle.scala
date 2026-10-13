package eu.ww86.hoconfmt

import java.util.regex.{Matcher, Pattern}
import scala.annotation.tailrec
import IncludeMasking.Masked

/** Snapshot of the old placeholder regex search: no literal-prefix prefilter.
  * Unchanged parsing and masking are shared so this oracle isolates the scan change.
  */
object PlaceholderFieldOracle {
  val PlaceholderPrefix = "__INCLUDE_"
  val GuardPrefix       = "__INCLUDE_GUARD_"
  val GuardValue        = "g"

  val OptionalQuote = """["]?"""

  /** Built from the same constants the placeholders are written with, so the two cannot drift.
    *
    * The separator matches `:` and `=` because the renderer writes the token the asked-for
    * [[FormatOptions.separator]] names, and both spellings are our own placeholders. The value's
    * index is captured rather than backreferenced to the key's, because Scala Native's
    * `java.util.regex` is RE2-based and has no backreferences; `unmask` compares the two.
    */
  val PlaceholderField: Pattern = Pattern.compile(
    s"""$OptionalQuote$PlaceholderPrefix(\\d+)$OptionalQuote\\s*[:=]""" +
      s"""\\s*$OptionalQuote$PlaceholderPrefix(\\d+)$OptionalQuote"""
  )

  // The renderer may or may not quote the guard value, so both spellings have to match.
  val guardField =
    s"""$OptionalQuote$GuardPrefix(\\d+)$OptionalQuote[ \\t]*[:=][ \\t]*$OptionalQuote$GuardValue$OptionalQuote"""
  val GuardField: Pattern        = Pattern.compile(guardField)
  val GuardOnItsOwnLine: Pattern = Pattern.compile(s"""\\n[ \\t]*$guardField""")
  val GuardInline: Pattern       = Pattern.compile(s""",?[ \\t]*$guardField""")

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

  def ours(masked: Masked): Int = 3 * masked.originals.size

  def occurrences(text: String): Int = {
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
  def indexOf(matched: Matcher): Option[Int] =
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

  def replaceEachMatch(text: String, pattern: Pattern)(replacement: Matcher => Option[String]): String = {
    val matcher = pattern.matcher(text)
    val out     = new StringBuilder
    @tailrec
    def collect(from: Int, copiedUpTo: Int): String = {
      if (!matcher.find(from)) out.append(text.substring(copiedUpTo)).toString
      else
        replacement(matcher) match {
          case Some(next) =>
            val _ = out.append(text.substring(copiedUpTo, matcher.start)).append(next)
            collect(matcher.end, matcher.end)
          case None => collect(matcher.start + 1, copiedUpTo)
        }
    }
    collect(0, 0)
  }
}
