package ww86.hocon_fmt

/** Pure decisions for the upward lookup. Callers supply filesystem facts and read the result. */
object ConfigLookup {
  enum Decision derives CanEqual {
    case Found, Stop, Parent
  }

  /** A config in the boundary directory still belongs to that repository. `.git` may be a file. */
  def decide(hasConfig: Boolean, hasGit: Boolean): Decision =
    if (hasConfig) Decision.Found else if (hasGit) Decision.Stop else Decision.Parent

}
