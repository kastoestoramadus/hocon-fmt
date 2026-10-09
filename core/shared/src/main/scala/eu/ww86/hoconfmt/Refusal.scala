package eu.ww86.hoconfmt

/** Why the formatter left a text alone. Every case means the file must not be written. */
enum Refusal derives CanEqual {

  /** Decoding the bytes leniently would replace the invalid ones, and writing that back would
    * destroy them.
    */
  case NotUtf8

  /** The input is not HOCON that sconfig can read. */
  case NotHocon(detail: String)

  /** The file is named as a format of its own that Lightbend's loader also reads, and this
    * formatter writes HOCON only. A round trip would hand back HOCON: a `.json` file's objects
    * reordered and its quoting gone, a `.properties` value such as a JDBC URL not even parseable.
    */
  case OtherFormat(format: String)

  /** sconfig rendered text it cannot read back: one of the defects in `SconfigDefectsSpec`. */
  case BrokenOutput(detail: String)

  /** sconfig drops a comment that no field follows. A comment carries no meaning, so no other
    * check notices; losing one still loses what someone wrote down.
    */
  case LostComment(text: String)

  /** sconfig drops an include in an object that a later definition of the same key replaces. The
    * include no longer mattered, but its text is gone all the same.
    */
  case LostInclude(statement: String)

  /** Formatting would carry a field across an include. A later definition wins, so the file would
    * resolve to other values. sconfig leaves fields sharing a line in no defined order, and
    * renders a key defined twice where it first appeared.
    */
  case MovedInclude(statement: String)

  /** Formatting would drop a definition on a path an include may write, in the object the include
    * stands in. The included file cannot be read at format time, so the definition may have been
    * what kept its values out of the path, and dropping it could let them through. The fields name
    * the path, the include's line and the definition's line, the two places to look at.
    */
  case ShadowedByInclude(path: String, includeLine: Int, definitionLine: Int)

  /** The text spells the name the include placeholders are written with, in a file that has an
    * include. Restoring the placeholders could not tell it from ours.
    */
  case ReservedName

  /** Formatting the output again would change it, so the file would never settle. sconfig renders
    * an unresolved merge as a comment banner that parses but grows on every pass.
    */
  case UnstableOutput

  def reason: String = this match {
    case NotUtf8                                              => "not valid UTF-8"
    case NotHocon(detail)                                     => s"not valid HOCON: $detail"
    case OtherFormat(format)                                  => s"a $format file, and hocon-fmt formats HOCON only"
    case BrokenOutput(detail)                                 => s"the output would not parse again: $detail"
    case LostComment(text)                                    => s"a comment would be lost: $text"
    case LostInclude(text)                                    => s"an include would be lost: $text"
    case MovedInclude(text)                                   => s"an include would change places with a field: $text"
    case ShadowedByInclude(path, includeLine, definitionLine) =>
      s"formatting would drop the definition on line $definitionLine, which may be what keeps the include on line $includeLine out of $path"
    case ReservedName   => "the text uses __INCLUDE_, which the formatter reserves for include placeholders"
    case UnstableOutput => "a second formatting pass would change the output again"
  }
}
