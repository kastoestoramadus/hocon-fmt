package ww86.hocon_fmt.site

class GroupsSpec extends munit.FunSuite {

  val entries = List(
    Contribution(Library.LightbendConfig, 815, "t", Theme.FormatterProposal, PrState.Closed, "n"),
    Contribution(Library.Sconfig, 600, "t", Theme.UnresolvedMerges, PrState.Open, "n"),
    Contribution(Library.Sconfig, 598, "t", Theme.UnresolvedMerges, PrState.Open, "n"),
    Contribution(Library.Sconfig, 515, "t", Theme.Comments, PrState.Released, "n")
  )

  test("libraries come in declaration order, themes within a library in declaration order") {
    val sections = Groups(entries)
    assertEquals(sections.map(_.library), List(Library.Sconfig, Library.LightbendConfig))
    assertEquals(
      sections(0).groups.map(_.theme),
      List(Theme.UnresolvedMerges, Theme.Comments)
    )
  }

  test("entries within a theme come newest first") {
    val groups = Groups(entries)(0).groups
    assertEquals(groups(0).entries.map(_.number), List(600, 598))
  }

  test("a theme or library with no entries is left out") {
    val sections = Groups(entries)
    assert(sections(0).groups.forall(g => g.theme != Theme.Performance))
    assertEquals(sections(1).groups.map(_.theme), List(Theme.FormatterProposal))
  }

  test("every entry lands in exactly one group") {
    val grouped = Groups(entries).flatMap(_.groups).flatMap(_.entries)
    assertEquals(grouped.sortBy(_.number), entries.sortBy(_.number))
  }
}
