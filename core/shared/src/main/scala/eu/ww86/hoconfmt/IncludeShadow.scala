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
  *   - the values that survived the merge, from the origins of the parsed tree: a value carries the
  *     path it stands at and the line of the field that put it there, so a definition whose path
  *     carries no value on its own line is one the formatting dropped.
  *
  * A dropped definition is refused over when the include could have written its path, that is when
  * it stands in the include's object after the include, and another definition of the same object
  * names the same path or one above or below it, and when its dropping could let the included
  * values merge where they did not:
  *
  *   - the definition writes a value (not an object), and a later definition makes the path an
  *     object again, which merges into what the include left there;
  *   - or the definition is an object with nothing inside it, which replaces what is not an object,
  *     and no later definition of that path follows, so nothing else of the file replaces the
  *     include's values either;
  *   - or the definition shares its line with another definition of the same path that keeps it out
  *     of the merged tree — one of the two writes a value, or this one is an object with nothing
  *     inside it, which vanishes into the other's object. Neither the merge's origins nor the
  *     rendering tell one of the two from the other — a line carries no column, and fields sharing
  *     a line come out in no defined order — so the survivor cannot vouch for the dropped one.
  *
  * A definition inside a piece of an array concatenation stands at positions the merged list
  * numbers differently, so the merged tree cannot answer for it at all: it is never read as kept,
  * and it is refused whenever the rest of the check could let the include's values through, rather
  * than skipped over a value that belongs to another element.
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

  /** The values the merge kept: each value of the parsed tree stands at a path, and its origin
    * names the line of the field that put it there. A definition survives exactly when its path and
    * its line are one of these pairs. The line alone cannot decide it, since `a.o = 3` writes the
    * object `a` and the value `a.o` on one line, and the object the merge keeps would make the
    * dropped leaf look kept. Neither can the pair decide between two definitions written on one
    * line, which share both halves; [[overshadowedOnItsLine]] names those, and the candidate filter
    * refuses one of the two rather than let the other vouch for it.
    *
    * The root's own origin is left out: it is the text's first line, which a definition can share
    * only by standing at the very top, where no include can stand before it. Objects, arrays and
    * values all carry theirs.
    */
  private def keptValues(root: ConfigObject): Set[(KeyPath, Int)] =
    root.entrySet.asScala.iterator
      .flatMap(entry => valuesAt(List(KeyPath.Segment.Name(entry.getKey)), entry.getValue))
      .toSet

  private def valuesAt(path: List[KeyPath.Segment], value: ConfigValue): Set[(KeyPath, Int)] = value match {
    case objectValue: ConfigObject =>
      objectValue.entrySet.asScala.iterator
        .flatMap(entry => valuesAt(path :+ KeyPath.Segment.Name(entry.getKey), entry.getValue))
        .toSet
        .incl(KeyPath(path) -> objectValue.origin.lineNumber)
    case list: ConfigList =>
      list.asScala.iterator.zipWithIndex
        .flatMap { case (item, at) => valuesAt(path :+ KeyPath.Segment.Index(at), item) }
        .toSet
        .incl(KeyPath(path) -> list.origin.lineNumber)
    case leaf => Set(KeyPath(path) -> leaf.origin.lineNumber)
  }

  /** Whether the merge kept the definition: a value stands at its path on its line. A definition
    * inside a piece of a concatenation is not read off the merged tree at all: the piece counts its
    * elements from zero and the merged list counts them across the pieces, so the path names
    * another element there and no value on it vouches for this definition.
    */
  private def survives(kept: Set[(KeyPath, Int)], definition: DuplicateReport.Definition): Boolean =
    definition.arrayPositionsTrusted && kept.contains((definition.keyPath, definition.line))

  private def first(outline: DuplicateReport.Outline, root: ConfigObject): Option[Refusal.ShadowedByInclude] = {
    val kept = keptValues(root)
    outline.includes
      .sortBy(_.line)
      .iterator
      .flatMap { include =>
        val inItsObject = outline.definitions.filter(definition => standsIn(definition, include))
        inItsObject.zipWithIndex
          .filter { case (definition, at) =>
            definition.position > include.position &&
            (!survives(kept, definition) || overshadowedOnItsLine(inItsObject, at, definition).nonEmpty)
          }
          .sortBy { case (definition, _) => definition.line }
          .iterator
          .flatMap { case (definition, at) =>
            Option.when[Refusal.ShadowedByInclude](shadowed(definition, at, include, inItsObject, root, kept))(
              Refusal.ShadowedByInclude(definition.keyPath.rendered, include.line, definition.line)
            )
          }
      }
      .nextOption()
  }

  /** The definitions beside `definition` on its line that keep it out of the merged tree: the same
    * path, and either the other writes a value the two cannot merge with, or this one is an object
    * with nothing inside it, which the other's object swallows whole — `p {}` beside `p { a = 1 }`
    * leaves nothing of its own to see in the rendering. The merged tree carries a path and a line
    * per value and no column, so which of the two a value at that path on that line came from
    * cannot be read off it: [[survives]] can vouch for neither.
    */
  private def overshadowedOnItsLine(
      inItsObject: Vector[DuplicateReport.Definition],
      at: Int,
      definition: DuplicateReport.Definition
  ): Vector[DuplicateReport.Definition] =
    inItsObject.zipWithIndex.collect {
      case (other, otherAt)
          if otherAt != at && other.line == definition.line && other.keyPath == definition.keyPath &&
            (DuplicateReport.erases(other, definition) || definition.writesNothing && other.objectValued) =>
        other
    }

  /** Whether a definition stands in the object an include stands in. Definitions elsewhere never
    * meet the include: the included file's fields are inlined into that object alone.
    */
  private def standsIn(definition: DuplicateReport.Definition, include: DuplicateReport.Include): Boolean =
    definition.arrayScope == include.arrayScope && definition.keyPath.segments.startsWith(include.scope.segments)

  /** Whether a definition the merge dropped could let the include's values through.
    *
    * The statements the walk passed give every definition its place in the source, so a definition
    * on the include's own line counts only when it stands after it: one in front of the include
    * cannot be what kept the included values out of the path.
    */
  private def shadowed(
      definition: DuplicateReport.Definition,
      at: Int,
      include: DuplicateReport.Include,
      inItsObject: Vector[DuplicateReport.Definition],
      root: ConfigObject,
      kept: Set[(KeyPath, Int)]
  ): Boolean = {
    val later =
      inItsObject.filter(_.position > definition.position) ++
        overshadowedOnItsLine(inItsObject, at, definition).filter(DuplicateReport.erases(_, definition))
    val related =
      inItsObject.zipWithIndex.exists { case (other, otherAt) =>
        otherAt != at && relatedPaths(other.keyPath, definition.keyPath)
      }
    if (!related) false
    else if (definition.objectValued) {
      // An object with nothing inside it adds nothing to a merge, so replacing a value that is
      // not an object is the only work it does. Only a definition the rendering keeps, between
      // the include and this one and on this path or a path below it, has made the path an
      // object already — a dropped one may have done nothing itself. And a later definition the
      // rendering keeps ends the merge with a value of its own.
      definition.writesNothing &&
      !later.exists(other => survives(kept, other) && sameOrBelow(other.keyPath, definition.keyPath)) &&
      !inItsObject.exists(other =>
        other.position > include.position && other.position < definition.position && survives(kept, other) &&
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
      ) && couldHoldObject(root, definition)
    }
  }

  /** Whether the merged tree could hold an object where a definition writes. A definition inside a
    * piece of a concatenation writes positions the merged list numbers differently, so the merged
    * tree cannot answer for it; the check assumes the worst rather than read the value of another
    * element, which would let the included file's values through unnoticed.
    */
  private def couldHoldObject(root: ConfigObject, definition: DuplicateReport.Definition): Boolean =
    !definition.arrayPositionsTrusted || objectAt(root, definition.keyPath)

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
