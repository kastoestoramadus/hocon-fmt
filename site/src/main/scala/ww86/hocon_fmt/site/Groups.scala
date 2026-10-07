package ww86.hocon_fmt.site

final case class ThemeGroup(theme: Theme, entries: List[Contribution])

final case class LibrarySection(library: Library, groups: List[ThemeGroup])

object Groups:

  /** The page's reading order: libraries in declaration order, themes within a library in
    * declaration order, entries within a theme by number descending. Empty groups and empty
    * libraries are left out, so the page never shows a heading with nothing under it.
    */
  def apply(entries: List[Contribution]): List[LibrarySection] = ???
