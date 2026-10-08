package ww86.hocon_fmt

class CommentCarrierDefaultSpec extends munit.FunSuite {

  test("the published core carries nothing: text and options pass through") {
    val source  = "a : 1\n# last\n"
    val carried = CommentCarrier.mask(source)
    assertEquals(carried.text, source)
    assertEquals(carried.restore("rendered"), "rendered")
    val options = HoconFormatter.parseOptions
    assert(CommentCarrier.parseOptions(options) eq options)
  }

  test("a comment no field follows is still refused") {
    assertEquals(HoconFormatter.format("o {\n  a : 1\n  # last\n}"), Left(Refusal.LostComment("last")))
  }

  test("the variant ledger is empty") {
    assertEquals(Variant.ledger, Map.empty[String, String])
  }
}
