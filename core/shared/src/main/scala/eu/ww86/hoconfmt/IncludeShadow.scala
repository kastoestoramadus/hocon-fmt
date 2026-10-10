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
  *   - or the definition shares its line with a later definition of the same path that erases it, so
  *     the value the merged tree carries at that path and line is the later one's own and cannot
  *     vouch for this one. The merge follows the source, and a doubt that looked both ways refused
  *     shapes like `include "f.conf", p {}, p = 3` whose pair carries the kept `p = 3`; only the
  *     later twin can have replaced the definition, so only it denies the vouch.
  *
  * A definition inside a piece of a concatenation is not read off the merged tree at all. An array
  * piece counts its elements from zero where the merged list counts them across the pieces, and an
  * object piece merges with the others: the merged value at a path and line a piece writes may be
  * the survivor of a definition the merge dropped. Such a definition is never read as kept, and it
  * is refused whenever the rest of the check could let the include's values through, rather than
  * skipped over a value that may belong to another element or another definition.
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
    * line that erase each other, which share both halves; [[survives]] denies the pair's vouch when
    * [[erasedByALaterLineTwin]] finds one, and the candidate filter refuses over both reads.
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

  /** Whether the merge kept the definition: a value stands at its path on its line, and no later
    * twin on that line erases it. A definition inside a piece of a concatenation is not read off the
    * merged tree at all: an array piece counts its elements from zero where the merged list counts
    * them across the pieces, and an object piece merges with the others, so the value at its path
    * and line may be the survivor of another definition and nothing there vouches for this one.
    *
    * A later erasing twin on the line is the same doubt in a smaller place: `q = 0` and `q {}`
    * written on one line erase each other, the merge follows the source, and the later one's value
    * is the value at the pair — so the pair carries the later one's origin and cannot vouch for the
    * earlier. `q=0, q.a=1, include "f.conf", q {}` showed what trusting it cost: the kept leaf
    * `q.a = 1` put a value at `q` on the line, that value vouched for the dropped `q {}` through
    * `q = 0`, and the refusal the empty object needed was never reached — the include's `q = 9`
    * then stayed where the empty object had cleared it.
    */
  private def survives(
      kept: Set[(KeyPath, Int)],
      definition: DuplicateReport.Definition,
      erasedByALaterLineTwin: Boolean
  ): Boolean =
    definition.arrayPositionsTrusted && kept.contains((definition.keyPath, definition.line)) && !erasedByALaterLineTwin

  private def first(outline: DuplicateReport.Outline, root: ConfigObject): Option[Refusal.ShadowedByInclude] = {
    val kept = keptValues(root)
    outline.includes
      .sortBy(_.line)
      .iterator
      .flatMap(include => firstStanding(include, outline.definitions, root, kept))
      .nextOption()
  }

  /** The first definition of an include's object to refuse over, in line order.
    *
    * The questions a refusal asks are about the definitions around one of them: what stands after
    * it, what stands between the include and it, and which paths are related to its own. Asking
    * them of one definition at a time scans the object once per definition, which a large file
    * with a concatenation pays for every definition — a definition inside a piece is never read off
    * the merged tree, so each of them is a candidate. Everything the answers read is therefore
    * indexed once per include and once per path: which the merge kept, what stands after a
    * position, what stands before one, and which paths hold a relative of another.
    */
  private def firstStanding(
      include: DuplicateReport.Include,
      definitions: Vector[DuplicateReport.Definition],
      root: ConfigObject,
      kept: Set[(KeyPath, Int)]
  ): Option[Refusal.ShadowedByInclude] = {
    val inItsObject = definitions.filter(definition => standsIn(definition, include))
    // One group per line and path, built once: the fallback below asks every definition whether
    // another one beside it on its line keeps it out of the merge, and scanning the whole object
    // for each of them is what made a large include-bearing file quadratic.
    val onItsLine = inItsObject.zipWithIndex.groupBy { case (definition, _) =>
      (definition.line, definition.keyPath)
    }
    val survivesAt = inItsObject.zipWithIndex.map { case (definition, at) =>
      survives(kept, definition, erasedByALaterLineTwin(onItsLine, at, definition))
    }
    val candidates = inItsObject.indices.filter { at =>
      val definition = inItsObject(at)
      definition.position > include.position &&
      (!survivesAt(at) || overshadowedOnItsLine(onItsLine, at, definition).nonEmpty)
    }
    if (candidates.isEmpty) None
    else {
      val around = new Around(include, inItsObject, survivesAt, onItsLine)
      candidates
        .sortBy(at => inItsObject(at).line)
        .iterator
        .flatMap { at =>
          Option.when[Refusal.ShadowedByInclude](refuses(inItsObject(at), at, around, root))(
            Refusal.ShadowedByInclude(inItsObject(at).keyPath.rendered, include.line, inItsObject(at).line)
          )
        }
        .nextOption()
    }
  }

  /** One include's object, and every answer a refusal reads off it, built once: which definitions
    * the merge kept, the groups sharing a line and path, the paths related to each other, and what
    * stands after a definition and between the include and it.
    */
  final private class Around(
      include: DuplicateReport.Include,
      definitions: Vector[DuplicateReport.Definition],
      survives: Vector[Boolean],
      onItsLine: Map[(Int, KeyPath), Vector[(DuplicateReport.Definition, Int)]]
  ) {

    private val related = new RelatedPaths(definitions)

    /** What the definitions after one another answer together: whether a survived definition stands
      * at its path or below it, whether an object stands at it, and whether one stands below it.
      */
    private val afterAll      = new PathCounts
    private val afterSurvived = new PathCounts
    private val afterObjects  = new PathCounts

    /** The same, for the definitions between the include and the one asked about — only the ones
      * the merge kept, since a dropped definition there did nothing.
      */
    private val beforeSurvived = new PathCounts
    private val beforeObjects  = new PathCounts

    private val after: Vector[(Boolean, Boolean, Boolean)] = definitions.indices.foldRight(Vector.empty) {
      (at, answers) =>
        val definition = definitions(at)
        val answer     = (
          afterSurvived.atOrBelow(definition.keyPath) > 0,
          afterObjects.exact(definition.keyPath) > 0,
          afterAll.below(definition.keyPath)
        )
        afterAll.add(definition.keyPath)
        if (survives(at)) afterSurvived.add(definition.keyPath)
        if (definition.objectValued) afterObjects.add(definition.keyPath)
        answer +: answers
    }

    private val before: Vector[(Boolean, Boolean)] = definitions.indices.foldLeft(Vector.empty) { (answers, at) =>
      val definition = definitions(at)
      val answer     =
        if (definition.position <= include.position) (false, false)
        else
          (
            beforeObjects.atOrBelow(definition.keyPath) > 0,
            beforeSurvived.below(definition.keyPath)
          )
      if (survives(at) && definition.position > include.position) {
        beforeSurvived.add(definition.keyPath)
        if (definition.objectValued) beforeObjects.add(definition.keyPath)
      }
      answers :+ answer
    }

    def keptAt(at: Int): Boolean                                            = survives(at)
    def overshadowingOf(at: Int): Vector[(DuplicateReport.Definition, Int)] =
      overshadowedOnItsLine(onItsLine, at, definitions(at))
    def relatedTo(at: Int): Boolean = related.relatedTo(at)

    /** Whether a survived definition stands after this one at its path or below it. */
    def afterAtOrBelow(at: Int): Boolean = after(at)._1

    /** Whether an object stands after this one at its path, whatever the merge kept of it. */
    def afterObjectAt(at: Int): Boolean = after(at)._2

    /** Whether anything stands after this one below its path. */
    def afterBelow(at: Int): Boolean = after(at)._3

    /** Whether a survived object stands between the include and this one at its path or below it. */
    def beforeObjectsAtOrBelow(at: Int): Boolean = before(at)._1

    /** Whether a survived definition stands between the include and this one below its path. */
    def beforeBelow(at: Int): Boolean = before(at)._2
  }

  /** Whether a definition standing later on the same line and path erases this one. The later one's
    * value is the value the merge kept at the pair, so the pair carries the later one's origin and
    * cannot vouch for the earlier — the doubt is one-sided: a twin in front cannot have replaced
    * anything, since the merge follows the source. `q=0, q.a=1, include "f.conf", q {}` is what the
    * doubt is for: `q = 0` is erased by both `q.a = 1` and `q {}`, so the pair did not vouch for it,
    * and the refusal `q {}` needed was reached even though the pair's value came from the leaf.
    */
  private def erasedByALaterLineTwin(
      onItsLine: Map[(Int, KeyPath), Vector[(DuplicateReport.Definition, Int)]],
      at: Int,
      definition: DuplicateReport.Definition
  ): Boolean =
    lineTwins(onItsLine, at, definition).exists { case (other, otherAt) =>
      otherAt > at && DuplicateReport.erases(other, definition)
    }

  /** The definitions beside `definition` on its line that keep it out of the merged tree: the same
    * path, and either the other writes a value the two cannot merge with, or this one is an object
    * with nothing inside it, which the other's object swallows whole — `p {}` beside `p { a = 1 }`
    * leaves nothing of its own to see in the rendering. The merged tree carries a path and a line
    * per value and no column, so which of the two a value at that path on that line came from
    * cannot be read off it: [[survives]] can vouch for neither. The group is read off
    * `onItsLine`, built once per include, and each definition comes with its place in it.
    */
  private def overshadowedOnItsLine(
      onItsLine: Map[(Int, KeyPath), Vector[(DuplicateReport.Definition, Int)]],
      at: Int,
      definition: DuplicateReport.Definition
  ): Vector[(DuplicateReport.Definition, Int)] =
    lineTwins(onItsLine, at, definition).filter { case (other, _) =>
      DuplicateReport.erases(other, definition) || definition.writesNothing && other.objectValued
    }

  /** The group of definitions sharing `definition`'s line and path, each with its place, this one
    * left out: what the two reads above ask of a line.
    */
  private def lineTwins(
      onItsLine: Map[(Int, KeyPath), Vector[(DuplicateReport.Definition, Int)]],
      at: Int,
      definition: DuplicateReport.Definition
  ): Vector[(DuplicateReport.Definition, Int)] =
    onItsLine.getOrElse((definition.line, definition.keyPath), Vector.empty).filter { case (_, otherAt) =>
      otherAt != at
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
  private def refuses(
      definition: DuplicateReport.Definition,
      at: Int,
      around: Around,
      root: ConfigObject
  ): Boolean = {
    val erasing = around.overshadowingOf(at).filter { case (other, _) => DuplicateReport.erases(other, definition) }
    if (!around.relatedTo(at)) false
    else if (definition.objectValued) {
      // An object with nothing inside it adds nothing to a merge, so replacing a value that is
      // not an object is the only work it does. Only a definition the rendering keeps, between
      // the include and this one and on this path or a path below it, has made the path an
      // object already — a dropped one may have done nothing itself. And a later definition the
      // rendering keeps ends the merge with a value of its own.
      definition.writesNothing &&
      !(around.afterAtOrBelow(at) ||
        erasing.exists { case (other, otherAt) =>
          around.keptAt(otherAt) && sameOrBelow(other.keyPath, definition.keyPath)
        }) &&
      !(around.beforeObjectsAtOrBelow(at) ||
        around.beforeBelow(at) ||
        erasing.exists { case (other, otherAt) =>
          around.keptAt(otherAt) &&
          (sameOrBelow(other.keyPath, definition.keyPath) && other.objectValued ||
            sitsBelow(definition.keyPath, other.keyPath))
        })
    } else {
      // A value that is not an object erases what came before, the included file's value
      // included. Losing it lets the included value merge into the object a later definition of
      // the path leaves behind; a merged tree that is not an object at the path means the
      // rendering ends with a value that erases the included one instead.
      (around.afterObjectAt(at) ||
        around.afterBelow(at) ||
        erasing.exists { case (other, _) =>
          (other.keyPath == definition.keyPath && other.objectValued) ||
          sitsBelow(definition.keyPath, other.keyPath)
        }) && couldHoldObject(root, definition)
    }
  }

  /** Which definitions of one object are related to another one — a definition of the same path, of
    * a path below it, or of a path above it. One lookup answers what [[refuses]] asks of every
    * candidate, where scanning the object for each of them was a scan per definition: the prefixes
    * of a path and the paths that hold a prefix of their own are read off sets.
    */
  final private class RelatedPaths(definitions: Vector[DuplicateReport.Definition]) {
    private val paths          = definitions.map(_.keyPath)
    private val counts         = paths.groupMapReduce(identity)(_ => 1)(_ + _)
    private val defined        = paths.toSet
    private val withDescendant = paths.flatMap(path => properPrefixes(path)).toSet

    def relatedTo(at: Int): Boolean = {
      val path = paths(at)
      counts.getOrElse(path, 0) > 1 ||
      withDescendant.contains(path) ||
      properPrefixes(path).exists(defined.contains)
    }
  }

  /** The paths a pass over the definitions has seen, counted at each path and at every path above
    * it: whether a definition writes a path or a path below it is then one lookup. The path itself
    * is counted too, so what stands strictly below it is the difference.
    */
  final private class PathCounts {
    private val atOrBelowCounts = scala.collection.mutable.HashMap.empty[KeyPath, Int]
    private val exactCounts     = scala.collection.mutable.HashMap.empty[KeyPath, Int]

    def add(path: KeyPath): Unit = {
      (path :: properPrefixes(path)).foreach { counted =>
        atOrBelowCounts.update(counted, atOrBelowCounts.getOrElse(counted, 0) + 1)
      }
      exactCounts.update(path, exactCounts.getOrElse(path, 0) + 1)
    }

    def atOrBelow(path: KeyPath): Int = atOrBelowCounts.getOrElse(path, 0)
    def exact(path: KeyPath): Int     = exactCounts.getOrElse(path, 0)

    /** Whether one of the paths seen stands strictly below `path`. */
    def below(path: KeyPath): Boolean = atOrBelow(path) > exact(path)
  }

  /** The paths above a path, from the one-segment path down to the full one but not including it. */
  private def properPrefixes(path: KeyPath): List[KeyPath] =
    path.segments.inits.filter(_.nonEmpty).drop(1).map(KeyPath(_)).toList

  /** Whether the merged tree could hold an object where a definition writes. A definition inside a
    * piece of a concatenation is not read off the merged tree — an array piece counts its elements
    * from zero where the merged list counts them across the pieces, and an object piece merges with
    * the others — so the check assumes the worst rather than read a value that may belong to
    * another element or another definition, which would let the included file's values through
    * unnoticed.
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

  private def sameOrBelow(one: KeyPath, other: KeyPath): Boolean = one == other || sitsBelow(other, one)

  /** Whether `inner` is a field inside `outer`, one or more steps down. */
  private def sitsBelow(outer: KeyPath, inner: KeyPath): Boolean =
    inner.segments.size > outer.segments.size && inner.segments.startsWith(outer.segments)
}
