package eu.ww86.hoconfmt

/** The pattern semantics of a `.gitignore`, as git matches them: what anchors a pattern, what the
  * wildcards cross, when a directory-only pattern applies, and which match decides.
  */
class GitIgnoreSpec extends munit.FunSuite {

  private def excludes(patterns: String, path: String, isDirectory: Boolean = false): Boolean =
    GitIgnore.parse(patterns).excluded(path, isDirectory)

  test("a pattern without a slash names a file or directory at any depth") {
    assert(excludes("*.conf", "a/b/x.conf"))
    assert(excludes("gen", "a/b/gen", isDirectory = true))
    assert(!excludes("*.conf", "a/b/x.txt"))
  }

  test("a pattern with a slash is anchored to the .gitignore's own directory") {
    assert(excludes("src/*.conf", "src/x.conf"))
    assert(!excludes("src/*.conf", "a/src/x.conf"))
    assert(!excludes("src/*.conf", "src/d/x.conf"))
    assert(excludes("/top.conf", "top.conf"))
    assert(!excludes("/top.conf", "sub/top.conf"))
  }

  test("a trailing slash restricts a pattern to directories") {
    assert(excludes("gen/", "gen", isDirectory = true))
    assert(!excludes("gen/", "gen", isDirectory = false))
    assert(!excludes("gen/", "a/gen/x.conf"))
  }

  test("* and ? do not cross a slash") {
    assert(!excludes("src/*.conf", "src/d/x.conf"))
    assert(excludes("res?.conf", "res1.conf"))
    assert(!excludes("res?.conf", "res10.conf"))
  }

  test("** spans directories where git allows it") {
    assert(excludes("**/gen", "gen", isDirectory = true))
    assert(excludes("**/gen", "a/b/gen", isDirectory = true))
    assert(excludes("a/**/b", "a/b"))
    assert(excludes("a/**/b", "a/x/y/b"))
    assert(!excludes("a/**/b", "a/xb"))
    assert(excludes("gen/**", "gen/x/y.conf"))
    assert(!excludes("gen/**", "gen"))
  }

  test("character classes match one character of a set") {
    assert(excludes("res[0-9].conf", "res0.conf"))
    assert(!excludes("res[0-9].conf", "resA.conf"))
    assert(excludes("res[!0-9].conf", "resA.conf"))
    assert(!excludes("res[!0-9].conf", "res0.conf"))
  }

  test("a later pattern wins, and ! re-includes") {
    assert(!excludes("*.conf\n!keep.conf", "keep.conf"))
    assert(excludes("!keep.conf\nkeep.conf", "keep.conf"))
  }

  test("comments, blank lines and escaped punctuation are not patterns") {
    assert(!excludes("# *.conf\n\n   \n", "x.conf"))
    assert(excludes("\\#real", "#real"))
    assert(excludes("with\\ space.conf", "with space.conf"))
  }

  test("trailing spaces are noise") {
    assert(excludes("x.conf   ", "x.conf"))
  }

  test("a pattern no regex can carry matches literally") {
    assert(excludes("a[.conf", "a[.conf"))
    assert(!excludes("a[.conf", "ax.conf"))
  }
}
