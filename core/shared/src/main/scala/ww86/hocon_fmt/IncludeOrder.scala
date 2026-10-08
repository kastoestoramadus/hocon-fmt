package ww86.hocon_fmt

import org.ekrich.config.{ConfigFactory, ConfigObject, ConfigValue}
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
private[hocon_fmt] object IncludeOrder {

  /** The first statement, in source order, whose position among the fields changed. `rendered` is
    * the masked text sconfig produced from `source`, so both name an include by the same index.
    * Output that cannot be read is no business of this check: the second pass refuses it.
    */
  def moved(source: String, rendered: String, originals: Map[Int, String]): Option[String] =
    if (originals.isEmpty) None
    else
      keysBefore(rendered, originals.keySet).toOption.flatMap { after =>
        // A source that reads in the masked form but not with its includes on lines of their own
        // is not understood well enough to vouch for: every position then counts as moved.
        val before =
          keysBefore(IncludeMasking.mask(source, onOwnLines = true).text, originals.keySet).getOrElse(Map.empty)
        originals.toList.sortBy(_._1).collectFirst {
          case (index, statement) if after.contains(index) && !before.get(index).contains(after(index)) => statement
        }
      }

  /** Where an include stands: the keys defined before it, and whether a key shares its line, in
    * which case sconfig's order says nothing.
    */
  private case class Position(before: Set[List[String]], tied: Boolean)

  private case class Leaf(path: List[String], line: Int, include: Option[Int])

  private val PlaceholderKey = (IncludeMasking.PlaceholderPrefix + """(\d+)""").r
  private val GuardKey       = (IncludeMasking.GuardPrefix + """(\d+)""").r

  private def keysBefore(masked: String, ours: Set[Int]): Try[Map[Int, Position]] =
    Try(ConfigFactory.parseString(masked, HoconFormatter.parseOptions).root).map { root =>
      val leaves = leavesOf(root, Nil, ours)
      leaves.collect { case Leaf(path, line, Some(index)) =>
        val inItsObject = leaves.filter(other => other.path.startsWith(path.init) && other.path != path)
        val before      = inItsObject.filter(_.line < line).map(_.path).toSet
        index -> Position(before, tied = inItsObject.exists(_.line == line))
      }.toMap
    }

  /** The values that are not objects, with the line each starts on. Our placeholders are told
    * apart the way `unmask` tells them: the key and the value carry the same index. Guards are
    * left out, since they are ours and moving them changes nothing.
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
            case inner: ConfigObject => leavesOf(inner, path, ours)
            case value               => leafOf(key, path, value, ours).toList
          }
        }
    }

  private def leafOf(key: String, path: List[String], value: ConfigValue, ours: Set[Int]): Option[Leaf] = {
    val line = value.origin.lineNumber
    key match {
      case GuardKey(index) if ours(index.toInt) && Try(value.unwrapped).toOption.contains(IncludeMasking.GuardValue) =>
        None
      case PlaceholderKey(index) if ours(index.toInt) && Try(value.unwrapped).toOption.contains(key) =>
        Some(Leaf(path, line, Some(index.toInt)))
      case _ => Some(Leaf(path, line, None))
    }
  }
}
