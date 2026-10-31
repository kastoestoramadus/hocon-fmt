package eu.ww86.hoconfmt

/** Why the formatter left a text alone. Every case means the file must not be written. */
enum Refusal derives CanEqual {

  /** Decoding the bytes leniently would replace the invalid ones, and writing that back would
    * destroy them.
    */
  case NotUtf8

  /** The input is not HOCON that sconfig can read. */
  case NotHocon(detail: String)

  /** An early lexical resource limit, shared by every platform before parsing. */
  case NestingTooDeep(limit: Int)

  /** The early lexical resource limits, found by the same scan: a dotted key path with more
    * segments than the written cap, or key paths whose total squared flattened length exceeds
    * the duplicate report's work budget. `detail` names the limit and what it bounds.
    */
  case PathTooDeep(detail: String)

  /** The scan's merge budget, found by the same walk: too many definitions merge through one
    * key — dotted spellings of its children or `+=` repeats of it — for the parser to resolve
    * them in bounded time, because sconfig's cost for them grows with the square of their
    * number. `detail` names the limit's remedy.
    */
  case SlowMerges(detail: String)

  /** The file is named as a format of its own that Lightbend's loader also reads, and this
    * formatter writes HOCON only. A round trip would hand back HOCON: a `.json` file's objects
    * reordered and its quoting gone, a `.properties` value such as a JDBC URL not even parseable.
    */
  case OtherFormat(format: String)

  /** Output cannot be read back safely, including after UTF-8 encoding: one of the defects in
    * `SconfigDefectsSpec`.
    */
  case BrokenOutput(detail: String)

  /** sconfig drops a comment that no field follows, and prints the comment of an appended field
    * twice. A comment carries no meaning, so no other check notices; losing one still loses what
    * someone wrote down, and a duplicate writes down what nobody did. The check compares the
    * output's comment multiset with the input's, so both are this case.
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

  /** The text spells the name a placeholder-writing pass of ours is written with — the comment
    * carrier's `__COMMENT_`, on the site build that carries one. Restoring the placeholders could
    * not tell it from ours. The include placeholders this once named are retired with their
    * masking pass; the case stays so older texts and the wire keep their meaning.
    */
  case ReservedName

  /** Formatting the output again would change it, so the file would never settle. sconfig renders
    * an unresolved merge as a comment banner that parses but grows on every pass.
    */
  case UnstableOutput

  def reason: String = this match {
    case NotUtf8                                              => "not valid UTF-8"
    case NotHocon(detail)                                     => s"not valid HOCON: $detail"
    case NestingTooDeep(limit)                                => s"input nesting exceeds the maximum depth of $limit"
    case PathTooDeep(detail)                                  => detail
    case SlowMerges(detail)                                   => detail
    case OtherFormat(format)                                  => s"a $format file, and hocon-fmt formats HOCON only"
    case BrokenOutput(detail)                                 => s"the output would not parse again: $detail"
    case LostComment(text)                                    => s"a comment would be lost or duplicated: $text"
    case LostInclude(text)                                    => s"an include would be lost: $text"
    case MovedInclude(text)                                   => s"an include would change places with a field: $text"
    case ShadowedByInclude(path, includeLine, definitionLine) =>
      s"formatting would drop the definition on line $definitionLine, which may be what keeps the include on line $includeLine out of $path"
    case ReservedName   => "the text spells a name the formatter reserves for its placeholders"
    case UnstableOutput => "a second formatting pass would change the output again"
  }
}
