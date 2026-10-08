package ww86.hocon_fmt.site

/** The shipped snapshot itself: completeness the refresh script can lean on, and defect rows
  * that resolve to entries which really exist.
  */
class ContributionsSpec extends munit.FunSuite:

  test("the snapshot carries every entry read from GitHub on the date it names") {
    assertEquals(Contributions.all.size, 58)
    assertEquals(Contributions.all.count(_.library == Library.Sconfig), 41)
    assertEquals(Contributions.all.count(_.library == Library.LightbendConfig), 17)
  }

  test("every entry has a title and a note that says something") {
    Contributions.all.foreach { entry =>
      assert(entry.title.trim.nonEmpty, s"#${entry.number} has no title")
      assert(entry.note.trim.length > 20, s"#${entry.number}'s note is too thin to be a sentence")
      assert(entry.note.endsWith("."), s"#${entry.number}'s note does not end its sentence")
    }
  }

  test("numbers are unique within a library") {
    val byLibrary = Contributions.all.groupBy(_.library)
    byLibrary.foreach { case (library, entries) =>
      val numbers = entries.map(_.number)
      assertEquals(numbers.distinct.size, numbers.size, s"duplicate numbers in $library")
    }
  }

  test("every note and every defect input splits into prose and code, never into a stray snippet") {
    val written = Contributions.all.map(_.note) ++ DefectTable.rows.map(_.defect)
    written.foreach { text =>
      assertEquals(text.count(_ == '`') % 2, 0, s"[$text] has an unclosed backtick")
      Prose.parts(text).foreach {
        case Prose.Part.Code(part) => assert(part.nonEmpty, s"[$text] has an empty code span")
        case Prose.Part.Text(_)    => ()
      }
    }
  }

  test("every defect row's fix resolves to a snapshot entry, in that library") {
    DefectTable.rows.flatMap(_.fixes).foreach { link =>
      val found = Contributions.byNumber(link.library, link.number)
      assert(found.isDefined, s"${link.library} #${link.number} is not in the snapshot")
    }
  }

  test("every defect row states what happens when nothing fixes it") {
    DefectTable.rows.foreach { row =>
      if row.fixes.isEmpty then assert(row.whenNoFix.nonEmpty, s"[${row.defect}] needs its note")
    }
  }

  test("the refusal names the defect table lists are the ones the web script's API publishes") {
    // `HoconFormatterJs.nameOf` is the API; the site spells the names out here so that renaming
    // one there cannot leave the page naming a refusal nothing answers to.
    assertEquals(
      RefusalKind.values.map(_.name).toSet,
      Set("notUtf8", "notHocon", "brokenOutput", "lostComment", "lostInclude", "movedInclude", "unstableOutput")
    )
  }

  test("the merge of the empty live answer is the snapshot, unchanged") {
    assertEquals(Merge(Contributions.all, Nil).entries, Contributions.all)
    assertEquals(Merge(Contributions.all, Nil).others, Nil)
  }

  test("the entries the groups produce are the snapshot, once each") {
    val grouped = Groups(Contributions.all).flatMap(_.groups).flatMap(_.entries)
    assertEquals(
      grouped.sortBy(c => (c.library.toString, c.number)),
      Contributions.all.sortBy(c => (c.library.toString, c.number))
    )
  }
