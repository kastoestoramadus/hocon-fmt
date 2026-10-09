package ww86.hocon_fmt

/** The options the command line gives a file. A field that is set wins over the config file; one
  * left unset leaves the file's own `.hocon-fmt.conf`, or the default, in charge.
  */
final case class StyleOverrides(
    separator: Option[Separator] = None,
    doubleIndent: Option[Boolean] = None,
    simplifyNestedObjects: Option[Boolean] = None,
    failOnDuplicates: Option[Boolean] = None
) derives CanEqual {

  def applyTo(base: FormatOptions): FormatOptions =
    FormatOptions(
      separator = separator.getOrElse(base.separator),
      doubleIndent = doubleIndent.getOrElse(base.doubleIndent),
      simplifyNestedObjects = simplifyNestedObjects.getOrElse(base.simplifyNestedObjects),
      failOnDuplicates = failOnDuplicates.getOrElse(base.failOnDuplicates)
    )
}

object StyleOverrides {
  val none: StyleOverrides = StyleOverrides()
}
