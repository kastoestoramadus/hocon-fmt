package ww86.hoconfmt.site

final case class ThemeGroup(theme: Theme, entries: List[Contribution])

final case class LibrarySection(library: Library, groups: List[ThemeGroup])

object Groups {

  /** The page's reading order: libraries in declaration order, themes within a library in
    * declaration order, entries within a theme by number descending. Empty groups and empty
    * libraries are left out, so the page never shows a heading with nothing under it.
    */
  def apply(entries: List[Contribution]): List[LibrarySection] =
    Library.values.toList.flatMap { library =>
      val ofLibrary = entries.filter(_.library == library)
      if ofLibrary.isEmpty then None
      else {
        val groups = Theme.values.toList.flatMap { theme =>
          val ofTheme = ofLibrary.filter(_.theme == theme).sortBy(-_.number)
          if ofTheme.isEmpty then None else Some(ThemeGroup(theme, ofTheme))
        }
        Some(LibrarySection(library, groups))
      }
    }
}
