package eu.ww86.hoconfmt

import org.ekrich.config.{ConfigList, ConfigObject, ConfigValue}

import scala.jdk.CollectionConverters.*
import scala.annotation.tailrec
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
    * [[LineTwins]] finds one, and the candidate filter refuses over both reads.
    *
    * The root's own origin is left out: it is the text's first line, which a definition can share
    * only by standing at the very top, where no include can stand before it. Objects, arrays and
    * values all carry theirs.
    */
  private def keptValues(root: ConfigObject): Set[(KeyPath, Int)] = {
    @tailrec
    def loop(pending: List[(List[KeyPath.Segment], ConfigValue)], kept: Set[(KeyPath, Int)]): Set[(KeyPath, Int)] =
      pending match {
        case Nil                   => kept
        case (path, value) :: rest =>
          val children = value match {
            case objectValue: ConfigObject =>
              objectValue.entrySet.asScala.toList.map(entry =>
                (path :+ KeyPath.Segment.Name(entry.getKey), entry.getValue)
              )
            case list: ConfigList =>
              list.asScala.toList.zipWithIndex.map { case (item, at) => (path :+ KeyPath.Segment.Index(at), item) }
            case _ => Nil
          }
          loop(children ::: rest, kept.incl(KeyPath(path) -> value.origin.lineNumber))
      }
    val children = root.entrySet.asScala.toList.map(entry => (List(KeyPath.Segment.Name(entry.getKey)), entry.getValue))
    loop(children, Set.empty)
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
    val kept     = keptValues(root)
    val includes = outline.includes.sortBy(_.line).zipWithIndex.groupBy { case (include, _) =>
      (include.arrayScope, include.scope)
    }
    val definitions = outline.definitions
      .flatMap { definition =>
        definition.keyPath.segments.inits
          .map(segments => (definition.arrayScope, KeyPath(segments)))
          .filter(includes.contains)
          .map(scope => scope -> definition)
      }
      .groupMap(_._1)(_._2)
    includes.iterator
      .flatMap { case (scope, statements) =>
        firstStanding(statements, definitions.getOrElse(scope, Vector.empty), root, kept)
      }
      .minByOption(_._1)
      .map(_._2)
  }

  /** Index an include-bearing scope once, then ask each doubtful definition which includes could
    * expose it. A surviving blocker between an include and an empty object rules that include
    * out: only includes at or after the last such blocker can refuse over the object.
    */
  private def firstStanding(
      includes: Vector[(DuplicateReport.Include, Int)],
      definitions: Vector[DuplicateReport.Definition],
      root: ConfigObject,
      kept: Set[(KeyPath, Int)]
  ): Option[(Int, Refusal.ShadowedByInclude)] = {
    val twins      = new LineTwins(definitions.zipWithIndex)
    val survivesAt = definitions.zipWithIndex.map { case (definition, at) =>
      survives(kept, definition, twins.erasedLater(at, definition))
    }
    val keptTwins = new LineTwins(definitions.zipWithIndex.filter { case (_, at) => survivesAt(at) })
    val around    = new Around(definitions, survivesAt, twins, keptTwins)

    // The document walk advances both line and position in source order; line ties retain that
    // order. A binary search finds the first include after a blocker without visiting the others.
    def includeAtOrAfter(position: Int): Option[(DuplicateReport.Include, Int)] = {
      @tailrec
      def lowerBound(from: Int, until: Int): Int =
        if (from == until) from
        else {
          val middle = from + (until - from) / 2
          if (includes(middle)._1.position < position) lowerBound(middle + 1, until)
          else lowerBound(from, middle)
        }
      includes.lift(lowerBound(0, includes.size))
    }

    definitions.indices.iterator
      .flatMap { at =>
        val definition = definitions(at)
        if ((survivesAt(at) && !twins.overshadowed(at, definition)) || !refuses(definition, at, around, root)) None
        else {
          val blocker = if (definition.objectValued) around.lastBlocker(at) else -1
          includeAtOrAfter(blocker)
            .filter(_._1.position < definition.position)
            .map[((Int, Int, Int), Refusal.ShadowedByInclude)] { case (include, rank) =>
              (
                (rank, definition.line, at),
                Refusal.ShadowedByInclude(definition.keyPath.rendered, include.line, definition.line)
              )
            }
        }
      }
      .minByOption(_._1)
      .map { case ((rank, _, _), refusal) => (rank, refusal) }
  }

  /** One scope's definitions, indexed independently of its includes: which definitions survived,
    * which twins erase them, which paths are related, and the suffix and latest-blocker facts.
    */
  final private class Around(
      definitions: Vector[DuplicateReport.Definition],
      survives: Vector[Boolean],
      twins: LineTwins,
      keptTwins: LineTwins
  ) {

    private val related = new RelatedPaths(definitions)

    /** What the definitions after one another answer together: whether a survived definition stands
      * at its path or below it, whether an object stands at it, and whether one stands below it.
      */
    private val afterAll      = new PathCounts
    private val afterSurvived = new PathCounts
    private val afterObjects  = new PathCounts

    /** Only surviving predecessors can block an include. Their latest position is the lower
      * bound for an include which could expose an empty object.
      */
    private val beforeSurvived = new PathLatest
    private val beforeObjects  = new PathLatest

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

    private val before: Vector[Int] = definitions.indices.foldLeft(Vector.empty) { (answers, at) =>
      val definition = definitions(at)
      val answer     = math.max(beforeObjects.atOrBelow(definition.keyPath), beforeSurvived.below(definition.keyPath))
      if (survives(at)) {
        beforeSurvived.add(definition.keyPath, definition.position)
        if (definition.objectValued) beforeObjects.add(definition.keyPath, definition.position)
      }
      answers :+ answer
    }

    def erasingKept(at: Int): Boolean       = keptTwins.erasing(at, definitions(at))
    def erasingObject(at: Int): Boolean     = twins.erasingObject(at, definitions(at))
    def erasingKeptObject(at: Int): Boolean = keptTwins.erasingObject(at, definitions(at))
    def relatedTo(at: Int): Boolean         = related.relatedTo(at)
    def afterAtOrBelow(at: Int): Boolean    = after(at)._1
    def afterObjectAt(at: Int): Boolean     = after(at)._2
    def afterBelow(at: Int): Boolean        = after(at)._3
    def lastBlocker(at: Int): Int           = before(at)
  }

  /** Erasure depends only on these two flags. At most four summaries answer a same-line query,
    * even if a line repeats one path thousands of times. Kept twins use the same summaries.
    */
  private case class Kind(objectValued: Boolean, holdsSubstitution: Boolean) derives CanEqual

  final private class LineTwins(definitions: Vector[(DuplicateReport.Definition, Int)]) {
    private val groups = definitions
      .groupBy { case (definition, _) => (definition.line, definition.keyPath) }
      .view
      .mapValues { entries =>
        entries
          .groupMap { case (definition, _) => Kind(definition.objectValued, definition.holdsSubstitution) }(_._2)
          .map { case (kind, indices) => kind -> (indices.toSet, indices.maxOption.getOrElse(-1)) }
      }
      .toMap

    private def erases(kind: Kind, definition: DuplicateReport.Definition): Boolean =
      !kind.holdsSubstitution && (!kind.objectValued || (!definition.objectValued && !definition.holdsSubstitution))

    def erasedLater(at: Int, definition: DuplicateReport.Definition): Boolean =
      groups.getOrElse((definition.line, definition.keyPath), Map.empty).exists { case (kind, (_, last)) =>
        last > at && erases(kind, definition)
      }

    private def other(at: Int, definition: DuplicateReport.Definition)(matches: Kind => Boolean): Boolean =
      groups.getOrElse((definition.line, definition.keyPath), Map.empty).exists { case (kind, (indices, _)) =>
        matches(kind) && indices.size > (if (indices(at)) 1 else 0)
      }

    def overshadowed(at: Int, definition: DuplicateReport.Definition): Boolean =
      other(at, definition)(kind => erases(kind, definition) || definition.writesNothing && kind.objectValued)

    def erasing(at: Int, definition: DuplicateReport.Definition): Boolean = other(at, definition)(erases(_, definition))

    def erasingObject(at: Int, definition: DuplicateReport.Definition): Boolean =
      other(at, definition)(kind => kind.objectValued && erases(kind, definition))
  }

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
    if (!around.relatedTo(at)) false
    else if (definition.objectValued) {
      // The last intervening blocker is checked against the include position separately. Twins
      // all name the same path, so a kept erasing twin already ends or starts that object's merge.
      definition.writesNothing &&
      !(around.afterAtOrBelow(at) || around.erasingKept(at)) && !around.erasingKeptObject(at)
    } else {
      (around.afterObjectAt(at) || around.afterBelow(at) || around.erasingObject(at)) && couldHoldObject(
        root,
        definition
      )
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

  /** Latest surviving positions, including strict descendants separately: a count cannot tell
    * whether the last blocker precedes or follows a particular include.
    */
  final private class PathLatest {
    private val atOrBelowPositions = scala.collection.mutable.HashMap.empty[KeyPath, Int]
    private val belowPositions     = scala.collection.mutable.HashMap.empty[KeyPath, Int]

    def add(path: KeyPath, position: Int): Unit = {
      (path :: properPrefixes(path)).foreach { prefix =>
        atOrBelowPositions.update(prefix, math.max(atOrBelow(prefix), position))
      }
      properPrefixes(path).foreach { prefix =>
        belowPositions.update(prefix, math.max(below(prefix), position))
      }
    }
    def atOrBelow(path: KeyPath): Int = atOrBelowPositions.getOrElse(path, -1)
    def below(path: KeyPath): Int     = belowPositions.getOrElse(path, -1)
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

  @tailrec
  private def valueAt(value: ConfigValue, segments: List[KeyPath.Segment]): Option[ConfigValue] =
    segments match {
      case Nil                                => Some(value)
      case KeyPath.Segment.Name(name) :: rest =>
        value match {
          case objectValue: ConfigObject =>
            Option(objectValue.get(name)) match {
              case Some(next) => valueAt(next, rest)
              case None       => None
            }
          case _ => None
        }
      case KeyPath.Segment.Index(at) :: rest =>
        value match {
          case list: ConfigList =>
            list.asScala.lift(at) match {
              case Some(next) => valueAt(next, rest)
              case None       => None
            }
          case _ => None
        }
    }

}
