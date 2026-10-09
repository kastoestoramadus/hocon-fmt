package ww86.hoconfmt

/** Pure decisions for the upward lookup. Callers supply filesystem facts and read the result. */
object ConfigLookup {
  enum Decision derives CanEqual {
    case Found, Stop, Parent

    /** Whether the walk found the config it was looking for. The Java API cannot name the cases
      * of a Scala enum across the JVM boundary, so it asks the decision instead of matching.
      */
    def found: Boolean = this == Decision.Found

    /** Whether the walk ends here, at a config boundary, instead of looking further up. */
    def stops: Boolean = this == Decision.Stop
  }

  /** A config in the boundary directory still belongs to that repository. `.git` may be a file. */
  def decide(hasConfig: Boolean, hasGit: Boolean): Decision =
    if (hasConfig) Decision.Found else if (hasGit) Decision.Stop else Decision.Parent

}
