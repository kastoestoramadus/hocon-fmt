package ww86.hocon_fmt

import scala.util.matching.Regex

/** The patterns of one `.gitignore`, matched the way git matches them: a pattern with no inner
  * slash names a file or directory at any depth, a trailing slash restricts the match to
  * directories, a leading `!` re-includes, and of the patterns that match, the last one decides.
  * `*` and `?` do not cross a slash; `**` does, where git allows it. A pattern no regex can carry
  * matches literally. What git reads beyond the checkout's files — `core.excludesFile`,
  * `.git/info/exclude` — this does not: the walk reads the `.gitignore` files it passes, and the
  * choice between several of them is [[Walk]]'s, which knows their order.
  */
final case class GitIgnore(patterns: List[GitIgnore.Pattern]) {

  /** Whether the path — '/'-separated, relative to the directory the `.gitignore` sits in — is
    * excluded: the last pattern that matches it wins, and no match excludes nothing.
    */
  def excluded(path: String, isDirectory: Boolean): Boolean =
    patterns.reverse.iterator.find(_.matches(path, isDirectory)).exists(!_.negate)
}

object GitIgnore {

  def parse(text: String): GitIgnore = GitIgnore(text.split("\n").toList.flatMap(pattern))

  final case class Pattern(negate: Boolean, directoryOnly: Boolean, source: String) {

    def matches(path: String, isDirectory: Boolean): Boolean =
      (!directoryOnly || isDirectory) && regex.matches(path)

    private lazy val regex = new Regex(source)
  }

  private def pattern(raw: String): Option[Pattern] = {
    val line = raw.stripSuffix("\r").replaceAll("[ \t]+$", "")
    if (line.isEmpty || line.startsWith("#")) None
    else {
      val (negate, unflagged)   = if (line.startsWith("!")) (true, line.drop(1)) else (false, line)
      val (directoryOnly, body) =
        if (unflagged.endsWith("/")) (true, unflagged.stripSuffix("/")) else (false, unflagged)
      val core = body.stripPrefix("/")
      if (core.isEmpty) None
      else {
        // A pattern with an inner slash is anchored to its directory, as git anchors it.
        val anchored = body.contains('/')
        val source   = if (anchored) s"^${translate(core)}$$" else s"^(?:.*/)?${translate(core)}$$"
        val safe     = usable(source).getOrElse(s"^${core.flatMap(literal)}$$")
        Some(Pattern(negate, directoryOnly, safe))
      }
    }
  }

  /** `**` is the only wildcard that crosses a slash, and only where git allows it: before a
    * slash it matches any directories, after one it matches any again, possibly none, and at the
    * end everything inside. Anywhere else it counts as the single-segment `*`. The translation
    * stays inside RE2 and ES2015: no lookaround, no backreferences.
    */
  private def translate(body: String): String = {
    val out                  = new StringBuilder
    def go(index: Int): Unit =
      if (index < body.length) {
        val rest = body.substring(index)
        if (rest.startsWith("**/")) { out.append("(?:.*/)?"); go(index + 3) }
        else if (rest.startsWith("/**/")) { out.append("/(?:.*/)?"); go(index + 4) }
        else if (rest == "/**") { out.append("/.*"); go(index + 3) }
        else if (rest.startsWith("**")) { out.append("[^/]*"); go(index + 2) }
        else
          body.charAt(index) match {
            case '*'                             => out.append("[^/]*"); go(index + 1)
            case '?'                             => out.append("[^/]"); go(index + 1)
            case '\\' if index + 1 < body.length =>
              out.append(literal(body.charAt(index + 1))); go(index + 2)
            case '[' =>
              val close = body.indexOf(']', index + 1)
              if (close <= index + 1) { out.append(literal('[')); go(index + 1) }
              else {
                val member = body.substring(index + 1, close)
                val inner  = if (member.startsWith("!")) "^" + member.drop(1) else member
                out.append('[')
                out.append(inner)
                out.append(']')
                go(close + 1)
              }
            case other => out.append(literal(other)); go(index + 1)
          }
      }
    go(0)
    out.toString
  }

  private def literal(c: Char): String = if ("\\.[]{}()*+?^$|".indexOf(c) >= 0) "\\" + c else c.toString

  // A class git would accept but a regex engine rejects, such as [z-a], still matches — literally.
  private def usable(source: String): Option[String] =
    try { new Regex(source); Some(source) }
    catch { case _: Exception => None }
}
