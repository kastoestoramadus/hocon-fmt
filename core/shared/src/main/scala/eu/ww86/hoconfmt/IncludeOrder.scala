package eu.ww86.hoconfmt

import org.ekrich.config.{ConfigFactory, ConfigList, ConfigObject, ConfigValue}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Finds the includes that formatting would move across a field.
  *
  * A later definition wins, so an include means what the fields before it in its object leave it.
  * sconfig renders fields in the order of the lines they start on, and an origin has no column:
  * fields sharing a line come out in no defined order, and a key defined twice is rendered once,
  * where it first appeared. Because an include is masked as a field, either carries it across its
  * neighbours, and the file then resolves to other values.
  *
  * The check compares, for every include, the keys defined before it in its object, as full paths
  * because formatting flattens nested keys. The source is read with each include on a line of its
  * own (`IncludeMasking.mask(_, onOwnLines = true)`), which gives it a position among the fields
  * around it that its own line would not.
  */
private[hoconfmt] object IncludeOrder {

  /** The first statement, in source order, whose position among the fields changed. `rendered` is
    * the masked text sconfig produced from `source`, so both name an include by the same index.
    * Output that cannot be read is no business of this check: the second pass refuses it.
    */
  def moved(source: String, rendered: String, originals: Map[Int, String]): Option[String] =
    if (originals.isEmpty) None
    else
      leavesIn(rendered, originals.keySet).toOption.flatMap { afterLeaves =>
        // An unreadable source has no vouched-for positions, just as in the original check.
        val beforeLeaves = leavesIn(IncludeMasking.mask(source, onOwnLines = true).text, originals.keySet).toOption
        val sets         = new PathSets(afterLeaves.size + beforeLeaves.fold(0)(_.size))
        val before       = beforeLeaves.map(keysBefore(_, sets)).getOrElse(Map.empty)
        val after        = keysBefore(afterLeaves, sets)
        originals.toList.sortBy(_._1).collectFirst {
          case (index, statement) if after.contains(index) && !before.get(index).contains(after(index)) => statement
        }
      }

  private case class Position(before: Int, tied: Boolean)

  private case class Leaf(path: List[String], line: Int, include: Option[Int])

  private val PlaceholderKey = (IncludeMasking.PlaceholderPrefix + """(\d+)""").r
  private val GuardKey       = (IncludeMasking.GuardPrefix + """(\d+)""").r
  // A regex group is `String | Null` to the compiler; `(\d+)` always takes part in a match.
  private object Index {
    def unapply(digits: String | Null): Option[Int] = Option(digits).flatMap(_.toIntOption)
  }

  private def leavesIn(masked: String, ours: Set[Int]): Try[List[Leaf]] =
    Try(ConfigFactory.parseString(masked, HoconFormatter.parseOptions).root).map(leavesOf(_, Nil, ours))

  /** Sweep whole lines before adding their paths, so a tied field never counts as preceding an
    * include. Each leaf contributes only to the enclosing scopes which actually hold includes.
    */
  private def keysBefore(leaves: List[Leaf], sets: PathSets): Map[Int, Position] = {
    val scopes    = leaves.collect { case Leaf(path, _, Some(_)) => path.dropRight(1) }.toSet
    val roots     = scala.collection.mutable.HashMap.empty[List[String], Int]
    val positions = scala.collection.mutable.HashMap.empty[Leaf, Position]
    leaves.groupBy(_.line).toList.sortBy(_._1).foreach { case (_, onLine) =>
      val inScopes = onLine
        .flatMap { leaf =>
          leaf.path.inits.filter(scopes.contains).map(scope => scope -> leaf)
        }
        .groupMap(_._1)(_._2)
      inScopes.foreach { case (scope, entries) =>
        val before = roots.getOrElse(scope, 0)
        val paths  = entries.map(_.path).toSet
        entries.foreach { leaf =>
          if (leaf.include.nonEmpty && leaf.path.dropRight(1) == scope)
            positions.update(leaf, Position(before, paths.size > 1))
        }
        roots.update(scope, entries.foldLeft(before)((root, leaf) => sets.add(root, leaf.path)))
      }
    }
    // Preserve the original tree walk's choice if more than one leaf names an issued index.
    leaves.collect { case leaf @ Leaf(_, _, Some(index)) => index -> positions(leaf) }.toMap
  }

  /** Canonical persistent sets of path IDs. Interning complete child pairs makes root equality
    * exact, including across the two parses; no fingerprint or probabilistic hash is trusted.
    * Each insertion copies O(log(paths)) nodes instead of copying all preceding paths per include.
    */
  final private class PathSets(maxPaths: Int) {
    private val paths    = scala.collection.mutable.HashMap.empty[List[String], Int]
    private val interned = scala.collection.mutable.HashMap.empty[(Int, Int), Int]
    // Zero is the empty subtree, one the occupied terminal; internal nodes start at two.
    private val nodes  = scala.collection.mutable.ArrayBuffer((0, 0), (0, 0))
    private val topBit = 31 - Integer.numberOfLeadingZeros(math.max(1, maxPaths))

    def add(root: Int, path: List[String]): Int = {
      val index                            = paths.getOrElseUpdate(path, paths.size)
      def insert(node: Int, bit: Int): Int =
        if (bit < 0) 1
        else {
          val (left, right) = nodes(node)
          val children      =
            if ((index & (1 << bit)) == 0) (insert(left, bit - 1), right)
            else (left, insert(right, bit - 1))
          interned.getOrElseUpdate(
            children, {
              val next = nodes.size
              val _    = nodes.addOne(children)
              next
            }
          )
        }
      insert(root, topBit)
    }
  }

  /** The values that are not objects, with the line each starts on. Our placeholders are told
    * apart the way `unmask` tells them: the key and the value carry the same index. Guards are
    * left out, since they are ours and moving them changes nothing. An array is a value of its own,
    * and the objects in it, reached by their position, hold fields and includes like any other.
    */
  private def leavesOf(obj: ConfigObject, at: List[String], ours: Set[Int]): List[Leaf] =
    // An object sconfig has not resolved (a merge with a substitution) cannot list its keys.
    Try(obj.entrySet.asScala.toList).toOption match {
      case None          => Nil
      case Some(entries) =>
        entries.flatMap { entry =>
          val key  = entry.getKey
          val path = at :+ key
          entry.getValue match {
            case inner: ConfigObject => placesOf(inner, path, ours)
            case list: ConfigList    => Leaf(path, list.origin.lineNumber, None) :: leavesInItems(list, path, ours)
            case value               => leafOf(key, path, value, ours).toList
          }
        }
    }

  /** Where an object stands among the fields: the leaves its contents write, or itself when it
    * holds none. An empty object writes a value of its own — an object with nothing inside it
    * replaces what is not an object — so an include that ends up crossing it changes what the file
    * resolves to, exactly as crossing a leaf would.
    */
  private def placesOf(obj: ConfigObject, path: List[String], ours: Set[Int]): List[Leaf] =
    leavesOf(obj, path, ours) match {
      case Nil if listsNoKeys(obj) => List(Leaf(path, obj.origin.lineNumber, None))
      case places                  => places
    }

  /** Whether the object lists no keys at all, as opposed to none this walk can see because the
    * merge behind it is unresolved.
    */
  private def listsNoKeys(obj: ConfigObject): Boolean =
    Try(obj.entrySet.asScala.isEmpty).getOrElse(false)

  private def leavesInItems(list: ConfigList, at: List[String], ours: Set[Int]): List[Leaf] =
    list.asScala.toList.zipWithIndex.flatMap {
      case (item: ConfigObject, i) => leavesOf(item, at :+ i.toString, ours)
      case (item: ConfigList, i)   => leavesInItems(item, at :+ i.toString, ours)
      case _                       => Nil
    }

  private def leafOf(key: String, path: List[String], value: ConfigValue, ours: Set[Int]): Option[Leaf] = {
    val line = value.origin.lineNumber
    key match {
      case GuardKey(Index(index)) if ours(index) && Try(value.unwrapped).toOption.contains(IncludeMasking.GuardValue) =>
        None
      case PlaceholderKey(Index(index)) if ours(index) && Try(value.unwrapped).toOption.contains(key) =>
        Some(Leaf(path, line, Some(index)))
      case _ => Some(Leaf(path, line, None))
    }
  }
}
