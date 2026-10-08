package ww86.hocon_fmt

import org.ekrich.config.{ConfigList, ConfigObject}
import org.scalacheck.Prop.forAll
import scala.jdk.CollectionConverters.*

import ww86.hocon_fmt.HoconFormatter.format
import ww86.hocon_fmt.HoconGen.*

/** Properties that must hold for every document, checked on generated ones.
  *
  * The example-based suites pin the cases someone thought of; these look for the ones nobody did.
  * On a failure ScalaCheck prints the shrunk document and the seed that reproduces it. The formatter
  * may always refuse; what it must never do is hand back text that lost something.
  */
class FormatterPropertiesSpec extends munit.ScalaCheckSuite with HoconTestSupport {

  val PlaceholderKey = "__INCLUDE_(\\d+)".r
  val GuardKey       = "__INCLUDE_GUARD_\\d+".r

  // The rarest defects found so far took thousands of documents to turn up; a deeper search is
  // `sbt -Dhocon.properties=20000 "coreJVM/testOnly ww86.hocon_fmt.FormatterPropertiesSpec"`.
  override def scalaCheckTestParameters = super.scalaCheckTestParameters
    .withMinSuccessfulTests(sys.props.get("hocon.properties").map(_.toInt).getOrElse(1000))

  property("never loses a comment") {
    forAll(documents(includes = true)) { doc =>
      format(doc.text).foreach { out =>
        doc.comments.foreach(c => assert(out.contains(c), s"comment [$c] lost from:\n$out"))
      }
    }
  }

  property("never loses an include") {
    forAll(documents(includes = true)) { doc =>
      format(doc.text).foreach { out =>
        doc.includes.foreach(i => assert(out.contains(i), s"[$i] lost from:\n$out"))
      }
    }
  }

  // A later definition wins, so an include sits where the fields before it say it does. Keys are
  // compared as full paths: formatting turns a nested key into a dotted one. Keys that merge or
  // replace each other are left out, since what counts as "before" is then a question of which
  // definition survives. The output is read through sconfig, with the includes masked as the
  // formatter does, so a shared misreading of its line numbers is what could hide a failure here;
  // the expectation comes from the generated tree and not from sconfig.
  property("keeps every key defined before an include before it") {
    val documentsWithUniqueIncludes = documents(includes = true, distinctKeys = true)
      .suchThat(doc => doc.includes.distinct == doc.includes)
    forAll(documentsWithUniqueIncludes) { doc =>
      format(doc.text).foreach { out =>
        assertEquals(keysBeforeIncludes(out), doc.keysBeforeIncludes, s"an include changed places in:\n$out")
      }
    }
  }

  property("never leaks a placeholder") {
    forAll(documents(includes = true)) { doc =>
      format(doc.text).foreach(out => assert(!out.contains("__INCLUDE"), out))
    }
  }

  property("keeps the meaning of every document it formats") {
    forAll(documents(includes = false)) { doc =>
      format(doc.text).foreach(out => assertSameMeaning(out, doc.text, s"meaning changed, output:\n$out"))
    }
  }

  // Without this the properties above would pass vacuously on a formatter that refused everything.
  property("formats every document it has no reason to refuse") {
    val documentsWithoutReason = documents(includes = true, distinctKeys = true)
      .suchThat(doc => doc.everyCommentPrecedesAField && !doc.hitsKnownSconfigDefect && !doc.includeSharesALine)
    forAll(documentsWithoutReason) { doc =>
      format(doc.text) match {
        case Right(out)    => assertEquals(format(out), Right(out), "output is not a fixed point")
        case Left(refusal) => fail(s"refused: ${refusal.reason}")
      }
    }
  }

  /** The same reading of formatted text as `Document.keysBeforeIncludes` makes of the tree. */
  def keysBeforeIncludes(formatted: String): Map[String, Set[List[String]]] = {
    val masked = IncludeMasking.mask(formatted)
    val leaves = leavesOf(masked.text.parsedConfig.root, Nil)
    leaves.collect { case Leaf(path, line, Some(index)) =>
      val parent = path.init
      val before = leaves.collect {
        case Leaf(other, l, None) if l < line && other.startsWith(parent) => other
        case Leaf(other, l, Some(i)) if l < line && other.init == parent  => HoconGen.includeKey(masked.originals(i))
      }
      masked.originals(index) -> before.toSet
    }.toMap
  }

  def leavesOf(obj: ConfigObject, at: List[String]): List[Leaf] =
    obj.entrySet.asScala.toList.flatMap { entry =>
      val path = at :+ entry.getKey
      (entry.getKey, entry.getValue) match {
        case (_, inner: ConfigObject)                                                         => leavesOf(inner, path)
        case (GuardKey(), _)                                                                  => Nil
        case (PlaceholderKey(index), value) if Option(value.unwrapped).contains(entry.getKey) =>
          List(Leaf(path, value.origin.lineNumber, Option(index).flatMap(_.toIntOption)))
        case (_, list: ConfigList) => Leaf(path, list.origin.lineNumber, None) :: leavesInItems(list, path)
        case (_, value)            => List(Leaf(path, value.origin.lineNumber, None))
      }
    }

  // The objects in an array are reached by their position; the other items are no leaves of their own.
  def leavesInItems(list: ConfigList, at: List[String]): List[Leaf] =
    list.asScala.toList.zipWithIndex.flatMap {
      case (item: ConfigObject, i) => leavesOf(item, at :+ i.toString)
      case (item: ConfigList, i)   => leavesInItems(item, at :+ i.toString)
      case _                       => Nil
    }
}

/** A value of parsed text: where it is, the line it starts on, and the include it stands for. */
final case class Leaf(path: List[String], line: Int, include: Option[Int])
