package ww86.hocon_fmt.site

import ww86.hocon_fmt.site.Prose.Part

class ProseSpec extends munit.FunSuite:

  test("plain words are one text part") {
    assertEquals(Prose.parts("no code here"), List(Part.Text("no code here")))
  }

  test("backticks mark the code parts, and the words between them stay text") {
    assertEquals(
      Prose.parts("`a : [1]` then `a += 2`"),
      List(Part.Code("a : [1]"), Part.Text(" then "), Part.Code("a += 2"))
    )
  }

  test("an unclosed backtick takes the rest of the text as code") {
    assertEquals(Prose.parts("see `docs"), List(Part.Text("see "), Part.Code("docs")))
  }

  test("an empty text renders as nothing at all") {
    assertEquals(Prose.parts(""), Nil)
  }

  test("a fragment with no words in it is dropped, not drawn as an empty box") {
    assertEquals(Prose.parts("a``b"), List(Part.Text("a"), Part.Text("b")))
  }
