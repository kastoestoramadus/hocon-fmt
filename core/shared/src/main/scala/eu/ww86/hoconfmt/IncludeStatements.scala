package eu.ww86.hoconfmt

import java.util.regex.Pattern

/** Locates `include` statements in a text: where each keyword stands, where the statement ends,
  * and the target as written. [[IncludeMasking]] builds its masked text from these spans and the
  * survival checks in [[HoconFormatter]] read a rendered text's statements from them, so the two
  * cannot drift on what counts as an include.
  *
  * The one definition this locator replaced — a keyword matcher walked inside `maskWithPrefix` —
  * produced the masked text and, as `mask(rendered).originals`, the statements of the rendered
  * text; one scanner now answers both.
  *
  * A character scanner like the masking pass, written with indices and loops; the suppression is
  * for this object only, not a precedent.
  */
@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While", "org.wartremover.warts.Return"))
private[hoconfmt] object IncludeStatements {

  /** One located statement: the keyword's start, one past the statement's end, and the target
    * text as written.
    */
  final case class Located(keywordStart: Int, end: Int, target: String)

  /** Every include statement of the text, in source order, as many times as it occurs. A keyword
    * inside another statement's target is part of that statement, not one of its own, and a
    * keyword outside code — in a comment, a string or prose — is left alone, as
    * `include_path` and a bare word are.
    */
  def locate(text: String): List[Located] = {
    val keywords     = IncludeKeyword.matcher(text)
    lazy val nonCode = HoconText.spans(text) // only a text that mentions include pays for it
    val found        = List.newBuilder[Located]
    var consumedUpTo = 0
    while (keywords.find())
      if (keywords.start >= consumedUpTo && HoconText.isCode(nonCode, keywords.start))
        targetAfterKeyword(text, keywords.end).foreach { target =>
          found += Located(keywords.start, target.endIndex, target.text)
          consumedUpTo = target.endIndex
        }
    found.result()
  }

  /** The statements as written, `include <target>`, in source order. */
  def statements(text: String): List[String] =
    locate(text).map(located => s"include ${located.target}")

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
