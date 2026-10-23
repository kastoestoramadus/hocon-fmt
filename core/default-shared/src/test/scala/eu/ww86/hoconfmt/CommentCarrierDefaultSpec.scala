package eu.ww86.hoconfmt

import org.ekrich.config.ConfigFactory

class CommentCarrierDefaultSpec extends munit.FunSuite {

  test("the published core carries nothing: text and options pass through") {
    val source  = "a : 1\n# last\n"
    val carried = CommentCarrier.mask(source)
    assertEquals(carried.text, source)
    assertEquals(carried.restore("rendered"), "rendered")
    assert(!carried.collides(ConfigFactory.parseString("a = \"__COMMENT_0\"").root), "the no-op judges nothing")
    val options = HoconFormatter.parseOptions
    assert(CommentCarrier.parseOptions(options) eq options)
    assert(!CommentCarrier.keepsDetachedComments, "the preamble path, not the parse, keeps a detached block here")
  }

  // the resolving render dropped a comment no field followed, which the guard refused; the
  // document engine keeps it where it stood
  test("a comment no field follows is kept") {
    assertEquals(HoconFormatter.format("o {\n  a : 1\n  # last\n}"), Right("o {\n  a = 1\n  # last\n}\n"))
  }

  test("the variant ledger is empty") {
    assertEquals(Variant.ledger, Map.empty[String, String])
  }
}
