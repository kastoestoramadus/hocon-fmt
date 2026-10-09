package eu.ww86.hoconfmt

import org.ekrich.config.{ConfigList, ConfigObject}
import org.scalacheck.Prop.forAll
import scala.jdk.CollectionConverters.*

import eu.ww86.hoconfmt.HoconGen.*

/** Properties that must hold for every document, checked on generated ones.
  *
  * The example-based suites pin the cases someone thought of; these look for the ones nobody did.
  * On a failure ScalaCheck prints the shrunk document and the seed that reproduces it. The formatter
  * may always refuse; what it must never do is hand back text that lost something. Every property
  * runs for every option combination: a style the user can ask for deserves the same guarantees.
  */
class FormatterPropertiesSpec extends munit.ScalaCheckSuite with HoconTestSupport {

  val PlaceholderKey = "__INCLUDE_(\\d+)".r
  val GuardKey       = "__INCLUDE_GUARD_\\d+".r

  /** Every combination the options can be set to. */
  val allOptions: List[FormatOptions] =
    for {
      separator             <- List(Separator.Equals, Separator.Colon)
      doubleIndent          <- List(false, true)
      simplifyNestedObjects <- List(true, false)
    } yield FormatOptions(separator, doubleIndent, simplifyNestedObjects)

  // The rarest defects found so far took thousands of documents to turn up; a deeper search is
  // `sbt -Dhocon.properties=20000 "coreJVM/testOnly eu.ww86.hoconfmt.FormatterPropertiesSpec"`.
  override def scalaCheckTestParameters = super.scalaCheckTestParameters
    .withMinSuccessfulTests(sys.props.get("hocon.properties").map(_.toInt).getOrElse(1000))

  property("never loses a comment, in any option combination") {
    forAll(documents(includes = true)) { doc =>
      allOptions.foreach { options =>
        HoconFormatter.format(doc.text, options).foreach { out =>
          doc.comments.foreach(c => assert(out.contains(c), s"comment [$c] lost from:\n$out"))
        }
      }
    }
  }

  property("never loses an include, in any option combination") {
    forAll(documents(includes = true)) { doc =>
      allOptions.foreach { options =>
        HoconFormatter.format(doc.text, options).foreach { out =>
          doc.includes.foreach(i => assert(out.contains(i), s"[$i] lost from:\n$out"))
        }
      }
    }
  }

  // A later definition wins, so an include sits where the fields before it say it does. Keys are
  // compared as full paths: formatting turns a nested key into a dotted one. Keys that merge or
  // replace each other are left out, since what counts as "before" is then a question of which
  // definition survives. The output is read through sconfig, with the includes masked as the
  // formatter does, so a shared misreading of its line numbers is what could hide a failure here;
  // the expectation comes from the generated tree and not from sconfig.
  property("keeps every key defined before an include before it, in any option combination") {
    val documentsWithUniqueIncludes = documents(includes = true, distinctKeys = true)
      .suchThat(doc => doc.includes.distinct == doc.includes)
    forAll(documentsWithUniqueIncludes) { doc =>
      allOptions.foreach { options =>
        HoconFormatter.format(doc.text, options).foreach { out =>
          assertEquals(keysBeforeIncludes(out), doc.keysBeforeIncludes, s"an include changed places in:\n$out")
        }
      }
    }
  }

  // What the report says about a document whose keys never repeat: nothing. The report does not
  // depend on the options, so one combination is enough.
  property("reports nothing on a document whose paths never repeat") {
    forAll(documentsWithConcatenations) { doc =>
      DuplicateReport.findings(doc.text) match {
        case Right(found)  => assertEquals(found, Nil, s"reported on:\n${doc.text}")
        case Left(refusal) => fail(s"the report refused a generated document: ${refusal.reason}\n${doc.text}")
      }
    }
  }

  property("never leaks a placeholder, in any option combination") {
    forAll(documents(includes = true)) { doc =>
      allOptions.foreach { options =>
        HoconFormatter.format(doc.text, options).foreach(out => assert(!out.contains("__INCLUDE"), out))
      }
    }
  }

  property("keeps the meaning of every document it formats, in any option combination") {
    forAll(documents(includes = false)) { doc =>
      allOptions.foreach { options =>
        HoconFormatter
          .format(doc.text, options)
          .foreach(out => assertSameMeaning(out, doc.text, s"meaning changed, output:\n$out"))
      }
    }
  }

  // User text that looks like what IncludeMasking writes, next to a real include: the formatter may
  // refuse the file, but a file it formats keeps that text.
  property("never alters text that looks like a placeholder, in any option combination") {
    import org.scalacheck.Gen
    val digits    = Gen.oneOf("0", "1", "01", "7", "99999999999", "999999999999999999999")
    val sep       = Gen.oneOf(" = ", " : ", "=", ":")
    val lookalike = for {
      n  <- digits
      s1 <- sep
      s2 <- sep
      t  <- Gen.oneOf(
             s"__INCLUDE_$n",
             s"__INCLUDE_GUARD_$n${s1}g",
             s"__INCLUDE_$n${s1}__INCLUDE_$n",
             s"x __INCLUDE_GUARD_$n${s2}g y"
           )
    } yield t
    def bareKey(text: String): String =
      "__INCLUDE_" + text.dropWhile(_ != '_').drop(10).takeWhile(c => c.isLetterOrDigit || c == '_')
    val place = Gen.oneOf("value", "quotedKey", "bareKey", "nested")
    forAll(lookalike, place) { (text, where) =>
      val source = where match {
        case "value"     => s"include \"f.conf\"\na = \"$text\""
        case "quotedKey" => s"include \"f.conf\"\n\"$text\" = 42"
        case "bareKey"   => s"include \"f.conf\"\n${bareKey(text)} = 5"
        case _           => s"o { include \"f.conf\"\n  a = \"$text\" }"
      }
      val kept = if (where == "bareKey") bareKey(text) else text
      allOptions.foreach { options =>
        HoconFormatter.format(source, options).foreach { out =>
          assert(out.contains(kept), s"[$kept] changed in:\n$out")
        }
      }
    }
  }

  property("refuses concatenated reserved spellings, in any option combination") {
    import org.scalacheck.Gen
    val collision = for {
      index      <- Gen.oneOf("0", "1", "01", "99999999999")
      guard      <- Gen.oneOf(false, true)
      split      <- Gen.choose(1, 9)
      quotedTail <- Gen.oneOf(false, true)
      place      <- Gen.oneOf("key", "value", "array", "nested")
    } yield {
      val text  = s"__INCLUDE_${if (guard) "GUARD_" else ""}$index"
      val tail  = text.drop(split)
      val token = s"\"${text.take(split)}\"" + (if (quotedTail) s"\"$tail\"" else tail)
      val field = place match {
        case "key"    => s"$token = ${if (guard) "g" else token}"
        case "array"  => s"a = [$token]"
        case "nested" => s"o { $token = g }"
        case _        => s"a = $token"
      }
      s"include \"f.conf\"\ninclude \"second.conf\"\n$field\n"
    }
    forAll(collision) { source =>
      allOptions.foreach { options =>
        assertEquals(HoconFormatter.format(source, options), Left(Refusal.ReservedName))
      }
    }
  }

  // Without this the properties above would pass vacuously on a formatter that refused everything.
  property("formats every document it has no reason to refuse, in any option combination") {
    val documentsWithoutReason = documents(includes = true, distinctKeys = true)
      .suchThat(doc => doc.everyCommentPrecedesAField && !doc.hitsKnownSconfigDefect && !doc.includeSharesALine)
    forAll(documentsWithoutReason) { doc =>
      allOptions.foreach { options =>
        HoconFormatter.format(doc.text, options) match {
          case Right(out) =>
            assertEquals(HoconFormatter.format(out, options), Right(out), "output is not a fixed point")
          case Left(refusal) => fail(s"refused: ${refusal.reason}")
        }
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
