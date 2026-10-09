package eu.ww86.hoconfmt.site

class MergeSpec extends munit.FunSuite {

  val snapshot = List(
    Contribution(Library.Sconfig, 598, "renderer", Theme.UnresolvedMerges, PrState.Open, "note"),
    Contribution(Library.Sconfig, 600, "merge entries", Theme.UnresolvedMerges, PrState.MergedUnreleased, "note"),
    Contribution(Library.Sconfig, 617, "MiMa", Theme.Project, PrState.Closed, "note"),
    Contribution(Library.LightbendConfig, 815, "formatting", Theme.FormatterProposal, PrState.Closed, "note")
  )

  def pr(number: Int, state: LiveState, title: String = "t") =
    LivePr(number, title, state)

  test("an open pull request stays open when the live search says open") {
    val result = Merge(snapshot, List(pr(598, LiveState.Open)))
    assertEquals(result.entries.head.state, PrState.Open)
    assertEquals(result.others, Nil)
  }

  test("a merge is told from a rejection: closed with merged_at raises, closed without closes") {
    val result = Merge(snapshot, List(pr(598, LiveState.Merged), pr(617, LiveState.ClosedUnmerged)))
    assertEquals(result.entries(0).state, PrState.MergedUnreleased)
    assertEquals(result.entries(2).state, PrState.Closed)
  }

  test("released and already-merged entries stand even when the search reports them merged") {
    val released = Contribution(Library.Sconfig, 611, "t", Theme.RendererRoundTrip, PrState.Released, "note")
    val result   = Merge(List(released, snapshot(1)), List(pr(611, LiveState.Merged), pr(600, LiveState.Merged)))
    assertEquals(result.entries(0).state, PrState.Released)
    assertEquals(result.entries(1).state, PrState.MergedUnreleased)
  }

  test("a pull request missing from the live answer keeps its snapshot state") {
    val result = Merge(snapshot, List(pr(600, LiveState.Merged)))
    assertEquals(result.entries.count(_.state == PrState.Open), 1)
    assertEquals(result.entries.count(_.state == PrState.Closed), 2)
  }

  test("a pull request unknown to the snapshot is listed under other recent work") {
    val result = Merge(snapshot, List(pr(642, LiveState.Open, "property tests")))
    assertEquals(result.others, List(LivePr(642, "property tests", LiveState.Open)))
  }

  test("others come newest first") {
    val result = Merge(snapshot, List(pr(642, LiveState.Open), pr(640, LiveState.Open)))
    assertEquals(result.others.map(_.number), List(642, 640))
  }

  test("an entry the live search knows nothing about is not an other") {
    val result = Merge(snapshot, List(pr(600, LiveState.Open)))
    assertEquals(result.others, Nil)
  }
}
