package eu.ww86.hoconfmt

import org.ekrich.config.{ConfigList, ConfigObject}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.jdk.CollectionConverters.*

import eu.ww86.hoconfmt.HoconGen.*

/** Properties checked on generated documents in the supported domain.
  *
  * The example-based suites pin the cases someone thought of; these look for the ones nobody did.
  * On a failure ScalaCheck prints the shrunk document and the seed that reproduces it. The formatter
  * may refuse a document with a known defect. Numeric spelling and number-to-string conversion
  * are a documented exception to meaning preservation; the generator uses canonical numbers.
  * Every property runs for every option combination.
  */
class FormatterPropertiesSpec extends munit.ScalaCheckSuite with HoconTestSupport {

  val StandInKey = "__READ_(\\d+)".r

  // The duplicate report was cubic in a dotted path's segments before the guard: a 10 KB file
  // (`a.a.a. ... = 1` with 2,000 segments) spent 24 seconds in it, through the command line on
  // every run. Whatever the length, the verdict and the report must come back without throwing
  // and in bounded time; the generous bound leaves slow CI machines out of the question.
  val pathTimeBoundMs = 10000L

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

  // The two properties that compared the formatter byte-for-byte with the frozen old pipeline
  // (placeholder scan, include indexes) are retired with the document engine: it formats what
  // the resolving render refused, so the pipelines disagree by design. Their generators stay in
  // use through the other properties; the replicas themselves (PlaceholderFieldOldFormatter,
  // IncludeChecksOldFormatter) and the include-order check are deleted with this retirement.

  property("nested include-bearing inputs always yield a verdict with a reason on refusal") {
    forAll(Gen.choose(1, 160)) { depth =>
      val source = "include \"missing.conf\"\n" + "a {" * depth + "x=1" + "}" * depth
      Verdict.of(source) match {
        case Verdict.Refused(refusal)      => assert(refusal.reason.nonEmpty)
        case Verdict.NeedsFormatting(text) => assert(text.nonEmpty)
        case Verdict.AlreadyFormatted      => ()
      }
    }
  }

  property("style parsing never throws at any generated nesting depth") {
    val depths = Gen.frequency(1 -> Gen.choose(0, 80), 4 -> Gen.choose(81, 10000))
    forAll(depths, Gen.oneOf("object", "array", "dotted"), Gen.oneOf("separator", "fail-on-refusal")) {
      (depth, shape, key) =>
        val text = shape match {
          case "object" => "style=" + "{a=" * depth + "1" + "}" * depth
          case "array"  => "style=" + "[" * depth + "1" + "]" * depth
          case _        => key + ".a" * depth + " = 1"
        }
        val result = FormatOptions.parse(text, "team.conf")
        assert(result.left.exists(_.startsWith("team.conf: ")), s"depth $depth: $result")
    }
  }

  property("format never throws at any generated nesting depth") {
    val depths = Gen.frequency(1 -> Gen.choose(0, 80), 4 -> Gen.choose(81, 10000))
    forAll(depths, Gen.oneOf(false, true), Gen.oneOf(false, true)) { (depth, arrays, include) =>
      val body =
        if (arrays) "a=" + "[" * depth + "1" + "]" * depth
        else "a {" * depth + "x=1" + "}" * depth
      val source = (if (include) "include \"missing.conf\"\n" else "") + body
      HoconFormatter.format(source) match {
        case Left(refusal) => assert(refusal.reason.nonEmpty)
        case Right(text)   => assert(text.nonEmpty)
      }
    }
  }

  property("no dotted key path length throws or exceeds a time bound") {
    forAll(Gen.choose(1, 3000)) { segments =>
      val source                               = (1 to segments).map(i => s"k$i").mkString(".") + " = 1\n"
      val withInclude                          = "include \"missing.conf\"\n" + source
      def bound[R](name: String)(run: => R): R = {
        val start  = System.nanoTime()
        val result = run
        val millis = (System.nanoTime() - start) / 1e6
        assert(millis < pathTimeBoundMs, s"$segments segments: $name took $millis ms")
        result
      }
      bound("verdict")(Verdict.of(source)) match {
        case Verdict.Refused(refusal) => assert(refusal.reason.nonEmpty)
        case _                        => ()
      }
      bound("duplicate report")(DuplicateReport.findings(source)).fold(
        refusal => assert(refusal.reason.nonEmpty),
        _ => ()
      )
      bound("duplicate report with an include")(DuplicateReport.findings(withInclude)).fold(
        refusal => assert(refusal.reason.nonEmpty),
        _ => ()
      )
    }
  }

  // Nesting writes in the flattened path what the single-line generator above cannot: every
  // enclosing object's key path adds its segments to every definition inside it, and that
  // flattened count is what the report's cost follows (a 20-level chain of 120-segment paths
  // spent 34 seconds in the report while every written line stayed under the limit; see
  // limitations.md). The generator mixes ordinary chains with ones past every limit, so both
  // the refusal and the surviving shapes are exercised. The value's spelling is part of the
  // shape: the written path must survive `=`, `:`, `+=` and the blanks after it to the `{`,
  // or a chain written that way slips past every bound. The historical 360-segment family gets
  // its own share; these large chains now exceed the total-work budget.
  property("no nested dotted path throws or exceeds a time bound") {
    val spelling = Gen.oneOf("brace", "eq", "colon", "append")
    val nesting  = Gen.frequency(
      4 -> Gen.zip(Gen.choose(1, 6), Gen.choose(1, 20), spelling),
      2 -> Gen.zip(Gen.choose(8, 14), Gen.choose(24, 40), spelling),
      1 -> Gen.zip(Gen.choose(1, 40), Gen.choose(1, 120), spelling)
    )
    def path(segments: Int): String                                 = (1 to segments).map(i => s"k$i").mkString(".")
    def chain(levels: Int, segments: Int, spelling: String): String = {
      val opener = spelling match {
        case "brace"  => path(segments) + " {\n"
        case "eq"     => path(segments) + " = {\n"
        case "colon"  => path(segments) + " : {\n"
        case "append" => path(segments) + " += {\n"
      }
      opener * levels + path(segments) + " = 1\n" + "}\n" * levels
    }
    forAll(nesting) { case (levels, segments, spelling) =>
      val source                               = chain(levels, segments, spelling)
      val withInclude                          = "include \"missing.conf\"\n" + source
      def bound[R](name: String)(run: => R): R = {
        val start  = System.nanoTime()
        val result = TimeoutGuard.within(pathTimeBoundMs)(run)
        val millis = (System.nanoTime() - start) / 1e6
        assert(millis < pathTimeBoundMs, s"$levels levels of $segments segments ($spelling): $name took $millis ms")
        result
      }
      bound("verdict")(Verdict.of(source)) match {
        case Verdict.Refused(refusal) => assert(refusal.reason.nonEmpty)
        case _                        => ()
      }
      bound("duplicate report")(DuplicateReport.findings(source)).fold(
        refusal => assert(refusal.reason.nonEmpty),
        _ => ()
      )
      bound("duplicate report with an include")(DuplicateReport.findings(withInclude)).fold(
        refusal => assert(refusal.reason.nonEmpty),
        _ => ()
      )
    }
  }

  // The dense shape behind the duplicate report's work budget: two 120-segment chains, 200
  // leaves of 120 segments each — the 160 KB shape that spent 12-13 s in the duplicate report
  // on the JVM while every written line stayed at the limit. The report the command line and
  // the integrations run (`DuplicateReport.findings`) must come back within the bound, as a
  // refusal once the budget is in force; on a regression this fails by TimeoutException instead
  // of hanging the suite. Scala.js's own parse ceiling refuses the shape first, so only a
  // non-empty reason is asserted, not who refused.
  property("the dense duplicate-report shape answers within the time bound") {
    def dense(leaves: Int): String = {
      val chain = (1 to 120).map(i => s"k$i").mkString(".")
      val leaf  = (i: Int) => (1 to 120).map(j => s"l${i}k$j").mkString(".")
      (chain + " {\n") * 2 + (1 to leaves).map(i => leaf(i) + " = 1\n").mkString + "}\n" * 2
    }
    forAll(Gen.choose(200, 400)) { leaves =>
      val source                               = dense(leaves)
      def bound[R](name: String)(run: => R): R = {
        val start  = System.nanoTime()
        val result = TimeoutGuard.within(pathTimeBoundMs)(run)
        val millis = (System.nanoTime() - start) / 1e6
        assert(millis < pathTimeBoundMs, s"the dense shape: $name took $millis ms")
        result
      }
      bound("duplicate report")(DuplicateReport.findings(source)).fold(
        refusal => assert(refusal.reason.nonEmpty),
        _ => ()
      )
      bound("verdict")(Verdict.of(source)) match {
        case Verdict.Refused(refusal) => assert(refusal.reason.nonEmpty)
        case _                        => ()
      }
    }
  }

  property("failure policies preserve the formatting verdict for every input") {
    val strict = FormatOptions
      .parse("fail-on-refusal = true\nfail-on-duplicates = true", "policy.conf")
      .fold(reason => fail(reason), identity)
    forAll(documents(includes = true)) { doc =>
      assertEquals(Verdict.of(doc.text, strict), Verdict.of(doc.text, FormatOptions.default))
    }
  }

  property("never loses a comment, in any option combination") {
    forAll(documents(includes = true)) { doc =>
      allOptions.foreach { options =>
        HoconFormatter.format(doc.text, options).foreach { out =>
          doc.comments.foreach(c => assert(out.contains(c), s"comment [$c] lost from:\n$out"))
        }
      }
    }
  }

  // Loss is the easy half: the render can also print a comment twice, as it does for the comment of
  // an appended field (`# c` above `a += 2` comes out once above the key and again inside the
  // expanded append — SconfigDefectsSpec pins that library bug). A file the formatter writes must
  // hold every comment exactly as often as its input did, so the check reads both as multisets.
  property("keeps the comment multiset exactly, in any option combination") {
    forAll(documents(includes = true)) { doc =>
      allOptions.foreach { options =>
        HoconFormatter.format(doc.text, options).foreach { out =>
          assertEquals(
            multiset(HoconText.comments(out)),
            multiset(HoconText.comments(doc.text)),
            s"the comment multiset changed in:\n$out"
          )
        }
      }
    }
  }

  def multiset(items: List[String]): Map[String, Int] =
    items.groupMapReduce(identity)(_ => 1)(_ + _)

  property("never loses an include, in any option combination") {
    forAll(documents(includes = true)) { doc =>
      allOptions.foreach { options =>
        HoconFormatter.format(doc.text, options).foreach { out =>
          doc.includes.foreach(i => assert(out.contains(i), s"[$i] lost from:\n$out"))
        }
      }
    }
  }

  // Escapes resolve in ordinary strings and quoted keys, but stay literal in comments,
  // triple-quoted strings and restored include targets. Raw halves also reach the text API.
  property("no accepted output contains an unpaired surrogate") {
    val piece = Gen.oneOf(
      "\\ud83d",
      "\\ude00",
      "\\ud83d\\ude00",
      "\\ude00\\ud83d",
      "\\udc00",
      "x",
      "\ud83d",
      "\ude00",
      "\ud83d\ude00"
    )
    val text     = Gen.listOf(piece).map(_.mkString)
    val template = Gen.oneOf[String => String](
      t => s"a = \"$t\"\n",
      t => s"\"$t\" = 1\n",
      t => s"a = \"\"\"$t\"\"\"\n",
      t => s"# $t\na = 1\n",
      t => s"include \"$t.conf\"\na = 1\n"
    )
    val sources = Gen.oneOf(
      Gen.zip(template, text).map { (make, t) => make(t) },
      documents(includes = true).map(_.text)
    )
    // Code units keep a failing counterexample printable even when the source is not encodable.
    forAll(sources.map(_.map(_.toInt).toList)) { units =>
      val source = units.map(_.toChar).mkString
      allOptions.foreach { options =>
        HoconFormatter.format(source, options).foreach { out =>
          val utf8 = java.nio.charset.StandardCharsets.UTF_8
          assert(
            String(out.getBytes(utf8), utf8) == out,
            s"not UTF-8 safe: ${out.map(c => f"\\u${c.toInt}%04x").mkString}"
          )
        }
      }
    }
  }

  // A later definition wins, so an include sits where the fields before it say it does. Keys are
  // compared as full paths: formatting turns a nested key into a dotted one. Keys that merge or
  // replace each other are left out, since what counts as "before" is then a question of which
  // definition survives. The output is read through sconfig, with the includes swapped for this
  // suite's stand-in fields (see [[maskedForRead]]), so a shared misreading of sconfig's line
  // numbers is what could hide a failure here; the expectation comes from the generated tree and
  // not from sconfig.
  property("keeps every key defined before an include before it, in any option combination") {
    val documentsWithUniqueIncludes = documents(includes = true, distinctKeys = true)
      .suchThat(doc => doc.includes.distinct == doc.includes)
      // A key sharing the include's line is ordered before it in the statement list, but the
      // reader below sees order through sconfig's line numbers, which do not order same-line
      // statements (SconfigDefectsSpec); IncludeOrderSpec pins those by exact output instead.
      .suchThat(doc => !doc.text.linesIterator.exists(line => line.contains(",") && doc.includes.exists(line.contains)))
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

  // docs/limitations.md promises that a cycle between bare names is not a refusal: whatever the
  // length of the ring, formatting settles it, and the second pass keeps what the first wrote.
  // Resolving that fixed point still fails with the same cycle complaint the input had — nothing
  // is hidden — so this pins the verdict and the stability, not a resolution.
  property("a pure substitution cycle of any length formats to a fixed point, in any option combination") {
    forAll(Gen.choose(2, 8)) { size =>
      // ScalaCheck shrinks toward 0, out of the generated range; a ring has at least two keys.
      val n = 2 max size
      allOptions.foreach { options =>
        val sep = options.separator match {
          case Separator.Equals => " = "
          case Separator.Colon  => ": "
        }
        val cycle = (0 until n).map(i => "k" + i + " : ${k" + ((i + 1) % n) + "}").mkString("\n")
        val fixed = (0 until n)
          .map(i => "k" + i + sep + "${k" + ((i + 1) % n) + "}")
          .mkString("", "\n", "\n")
        HoconFormatter.format(cycle, options) match {
          case Right(out) =>
            assertEquals(out, fixed, s"ring of $n, $options")
            assertEquals(HoconFormatter.format(out, options), Right(out), s"second pass, ring of $n, $options")
          case Left(refusal) => fail(s"ring of $n, $options: refused: ${refusal.reason}")
        }
      }
    }
  }

  // User text that looks like the __INCLUDE_ placeholders the retired masking pass wrote, next to
  // a real include: the formatter may refuse the file, but a file it formats keeps that text.
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

  // The concatenated-reserved-spellings property is gone with the masking pass it protected: the
  // document engine writes no placeholders, so there is no reserved spelling to refuse, and the
  // "never alters text that looks like a placeholder" property above pins that such text formats.

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

  /** The include statements of a formatted text swapped in place for stand-in fields, with the
    * statements keyed by their index: the resolving parse this property reads line numbers through
    * cannot parse an include on Scala.js, so the reader (not the formatter - the retired masking
    * pass was the formatter's) hands it text it can parse. A stand-in spells a key no generated
    * document writes.
    */
  private def maskedForRead(text: String): (String, Map[Int, String]) = {
    val out       = new StringBuilder
    val originals = Map.newBuilder[Int, String]
    var copied    = 0
    var index     = 0
    IncludeStatements.locate(text).foreach { located =>
      val standIn = s"__READ_$index"
      val _       = out.append(text.substring(copied, located.keywordStart)).append(s"$standIn = \"$standIn\"")
      originals += index -> s"include ${located.target}"
      copied = located.end
      index += 1
    }
    val _ = out.append(text.substring(copied))
    (out.result(), originals.result())
  }

  /** The same reading of formatted text as `Document.keysBeforeIncludes` makes of the tree. */
  def keysBeforeIncludes(formatted: String): Map[String, Set[List[String]]] = {
    val (text, originals) = maskedForRead(formatted)
    val leaves            = leavesOf(text.parsedConfig.root, Nil)
    leaves.collect { case Leaf(path, line, Some(index)) =>
      val parent = path.init
      val before = leaves.collect {
        case Leaf(other, l, None) if l < line && other.startsWith(parent) => other
        case Leaf(other, l, Some(i)) if l < line && other.init == parent  => HoconGen.includeKey(originals(i))
      }
      originals(index) -> before.toSet
    }.toMap
  }

  def leavesOf(obj: ConfigObject, at: List[String]): List[Leaf] =
    obj.entrySet.asScala.toList.flatMap { entry =>
      val path = at :+ entry.getKey
      (entry.getKey, entry.getValue) match {
        case (_, inner: ConfigObject)                                                     => leavesOf(inner, path)
        case (StandInKey(index), value) if Option(value.unwrapped).contains(entry.getKey) =>
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
