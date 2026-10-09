package ww86.hoconfmt

import org.ekrich.config.{ConfigList, ConfigObject, ConfigValue}

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Whether a parsed tree spells a masking pass's prefix anywhere but in the key/value pairs that
  * pass generated. [[IncludeMasking]] and the site's `CommentCarrier` judge their trees with this,
  * so a spelling the source reading let through refuses the file instead of restoring on a guess.
  * The `CommentCarrier` half goes when the seam does (docs/site.md).
  */
private[hoconfmt] object PlaceholderTree {

  /** Check field pairs, not independent allowed strings: a placeholder value under a user key is
    * still the user's value. Unresolved values cannot be unwrapped, but their rendering exposes
    * substitution paths and the pieces of unresolved concatenations and merges.
    */
  def collides(value: ConfigValue, prefix: String, generated: Map[String, String]): Boolean = value match {
    case obj: ConfigObject =>
      // An unresolved object merge cannot enumerate its keys, but rendering exposes its pieces.
      Try(obj.entrySet.asScala.toList).toOption match {
        case None          => renderedReserved(obj, prefix)
        case Some(entries) =>
          entries.exists { entry =>
            val key   = entry.getKey
            val child = entry.getValue
            val ours  = generated.get(key).exists(expected => Try(child.unwrapped).toOption.contains(expected))
            !ours && (key.contains(prefix) || collides(child, prefix, generated))
          }
      }
    case list: ConfigList => list.asScala.exists(collides(_, prefix, generated))
    case other            =>
      Try(other.unwrapped).toOption match {
        case Some(text: String) => text.contains(prefix)
        case Some(_)            => false
        case None               => renderedReserved(other, prefix)
      }
  }

  /** A failed rendering hides nothing: the pipeline refuses it before producing output. */
  private def renderedReserved(value: ConfigValue, prefix: String): Boolean =
    Try(value.render(HoconFormatter.renderOptions)).toOption.exists(_.contains(prefix))
}
