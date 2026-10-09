package eu.ww86.hoconfmt

import org.ekrich.config.{ConfigList, ConfigObject, ConfigValue}

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Finds the definitions whose dropping would let an include's contents through.
  *
  * An include is masked as a placeholder field before the parse, so sconfig resolves the text as
  * if the included file said nothing: a definition that a later one of the same path replaces is
  * dropped, and an object that merges into an object beside it leaves nothing behind. Either
  * definition may have been exactly what kept the included file's values out of the path, since
  * the included file is inlined where the directive stands. The rendered output carries the
  * include, so the dropped definition is gone from it and the included values may then take effect
  * where the file resolves them away today.
  *
  * The check reads both sides of the question from what the formatter already has:
  *
  *   - the definitions and includes, from the document tree of the text sconfig parsed (the masked
  *     text, whose lines are the parse's own), and
  *   - the lines that survived the merge, from the origins of the parsed tree: a value carries the
  *     line of the field that put it there, so a definition's line that no value carries is one the
  *     formatting dropped.
  *
  * A dropped definition is refused over when the include could have written its path, that is when
  * it stands in the include's object and another definition of the same object names the same path
  * or one above or below it, and when its dropping could let the included values merge where they
  * did not:
  *
  *   - the definition writes a value (not an object), and a later definition makes the path an
  *     object again, which merges into what the include left there;
  *   - or the definition is an object with nothing inside it, which replaces what is not an object,
  *     and no later definition of that path follows, so nothing else of the file replaces the
  *     include's values either.
  *
  * Refusing a little too much is the point: the included file cannot be read at format time (a
  * web page has no filesystem, and the include may name a URL), so the contents that could arrive
  * are unknown, and a file is only formatted when every definition that could matter survives.
  * The refusal names the include's line and the definition's line for the user to look at.
  */
private[hoconfmt] object IncludeShadow {

  /** The first definition to refuse over, or none: the refusal names the path it writes, the
    * include's line and its own line.
    */
  def shadowed(
      masked: String,
      originals: Map[Int, String],
      root: ConfigObject
  ): Option[Refusal.ShadowedByInclude] = {
    // Without an include no dropped definition can let anything through.
    if (originals.isEmpty) None
    else
      // Neither text is a text this check can judge, and each is refused by the formatter's own
      // checks: the document parser reads the masked text the config parse has just read, and an
      // object the merge could not resolve — which only the page's fork renders at all — lists no
      // keys, so its definitions cannot be seen.
      Try(DuplicateReport.outline(masked).toOption.flatMap(outline => first(outline, root))).toOption.flatten
  }

  /** The lines the merge kept something on. The root's own origin is left out: it is the text's
    * first line, which a definition can share only by standing at the very top, where no include
    * can stand before it. Objects, arrays and values all carry theirs.
    */
  private def linesOfTree(root: ConfigObject): Set[Int] =
    root.entrySet.asScala.iterator.flatMap(entry => linesOf(entry.getValue)).toSet

  private def linesOf(value: ConfigValue): Set[Int] = value match {
    case objectValue: ConfigObject =>
      objectValue.entrySet.asScala.iterator
        .flatMap(entry => linesOf(entry.getValue))
        .toSet
        .incl(objectValue.origin.lineNumber)
    case list: ConfigList =>
      list.asScala.iterator.flatMap(linesOf).toSet.incl(list.origin.lineNumber)
    case leaf => Set(leaf.origin.lineNumber)
  }

  private def first(outline: DuplicateReport.Outline, root: ConfigObject): Option[Refusal.ShadowedByInclude] = {
    val survived = linesOfTree(root)
    outline.includes
      .sortBy(_.line)
      .iterator
      .flatMap { include =>
        val inItsObject = outline.definitions.filter(definition => standsIn(definition, include))
        inItsObject
          .filter(definition => definition.line > include.line && !survived(definition.line))
          .sortBy(_.line)
          .iterator
          .flatMap(definition => shadowOf(definition, include, inItsObject, root, survived))
      }
      .nextOption()
  }

  /** Whether a definition stands in the object an include stands in. Definitions elsewhere never
    * meet the include: the included file's fields are inlined into that object alone.
    */
  private def standsIn(definition: DuplicateReport.Definition, include: DuplicateReport.Include): Boolean =
    definition.arrayScope == include.arrayScope && definition.keyPath.segments.startsWith(include.scope.segments)

  private def shadowOf(
      definition: DuplicateReport.Definition,
      include: DuplicateReport.Include,
      inItsObject: Vector[DuplicateReport.Definition],
      root: ConfigObject,
      survived: Set[Int]
  ): Option[Refusal.ShadowedByInclude] = {
    val later   = inItsObject.filter(_.line > definition.line)
    val related =
      inItsObject.exists(other => other.line != definition.line && relatedPaths(other.keyPath, definition.keyPath))
    val shadowed =
      if (!related) false
      else if (definition.objectValued) {
        // An object with nothing inside it adds nothing to a merge, so replacing a value that is
        // not an object is the only work it does. Only a definition the rendering keeps, between
        // the include and this one and on this path or a path below it, has made the path an
        // object already — a dropped one may have done nothing itself. And a later definition the
        // rendering keeps ends the merge with a value of its own.
        definition.writesNothing &&
        !later.exists(other => survived(other.line) && sameOrBelow(other.keyPath, definition.keyPath)) &&
        !inItsObject.exists(other =>
          other.line > include.line && other.line < definition.line && survived(other.line) &&
            (sameOrBelow(other.keyPath, definition.keyPath) && other.objectValued ||
              sitsBelow(definition.keyPath, other.keyPath))
        )
      } else {
        // A value that is not an object erases what came before, the included file's value
        // included. Losing it lets the included value merge into the object a later definition of
        // the path leaves behind; a merged tree that is not an object at the path means the
        // rendering ends with a value that erases the included one instead.
        later.exists(other =>
          (other.keyPath == definition.keyPath && other.objectValued) ||
            sitsBelow(definition.keyPath, other.keyPath)
        ) && objectAt(root, definition.keyPath)
      }
    Option.when(shadowed)(
      Refusal.ShadowedByInclude(definition.keyPath.rendered, include.line, definition.line)
    )
  }

  /** Whether the merged tree holds an object at the path. */
  private def objectAt(root: ConfigObject, path: KeyPath): Boolean =
    valueAt(root, path.segments) match {
      case Some(_: ConfigObject) => true
      case _                     => false
    }

  private def valueAt(value: ConfigValue, segments: List[KeyPath.Segment]): Option[ConfigValue] =
    segments match {
      case Nil                                => Some(value)
      case KeyPath.Segment.Name(name) :: rest =>
        value match {
          case objectValue: ConfigObject => Option(objectValue.get(name)).flatMap(valueAt(_, rest))
          case _                         => None
        }
      case KeyPath.Segment.Index(at) :: rest =>
        value match {
          case list: ConfigList => list.asScala.lift(at).flatMap(valueAt(_, rest))
          case _                => None
        }
    }

  /** Whether either path is the other, or a field inside it. */
  private def relatedPaths(one: KeyPath, other: KeyPath): Boolean =
    one == other || sitsBelow(one, other) || sitsBelow(other, one)

  private def sameOrBelow(one: KeyPath, other: KeyPath): Boolean = one == other || sitsBelow(other, one)

  /** Whether `inner` is a field inside `outer`, one or more steps down. */
  private def sitsBelow(outer: KeyPath, inner: KeyPath): Boolean =
    inner.segments.size > outer.segments.size && inner.segments.startsWith(outer.segments)
}
