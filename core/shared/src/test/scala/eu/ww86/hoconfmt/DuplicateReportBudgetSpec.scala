package eu.ww86.hoconfmt

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class DuplicateReportBudgetSpec extends munit.ScalaCheckSuite {

  // Keep the original all-candidates algorithm as an oracle for the index. Small documents
  // exercise replacement, merging, substitution, array scopes and suppression of child warnings.
  def reference(definitions: Vector[DuplicateReport.Definition]): List[Finding] = {
    def prefixes(path: KeyPath): Vector[KeyPath] =
      (1 to path.segments.size).toVector.map(n => KeyPath(path.segments.take(n)))
    val killed = definitions.zipWithIndex.flatMap { case (earlier, at) =>
      definitions.zipWithIndex
        .filter { case (later, next) =>
          next > at && later.arrayScope == earlier.arrayScope &&
          prefixes(earlier.keyPath).contains(later.keyPath) &&
          (if (later.keyPath == earlier.keyPath) DuplicateReport.erases(later, earlier)
           else !later.objectValued && !later.holdsSubstitution)
        }
        .minByOption(_._2)
        .map { case (later, _) => (earlier, later) }
    }
    val covered = killed.map { case (earlier, later) =>
      (earlier.arrayScope, earlier.line, later.line, earlier.keyPath)
    }.toSet
    killed
      .filterNot { case (earlier, later) =>
        prefixes(earlier.keyPath).dropRight(1).exists { path =>
          covered.contains((earlier.arrayScope, earlier.line, later.line, path))
        }
      }
      .map { case (earlier, later) => Finding.KeyDefinedAgain(earlier.keyPath, earlier.line, later.line) }
      .toList
  }

  val statement: Gen[String] = for {
    key       <- Gen.oneOf("a", "a.b", "a.b.c", "b", "b.c", "\"a.b\"")
    value     <- Gen.oneOf("1", "2", "{}", "{ b = 1, b = 2 }", "${x}", "${?X}", "[{ b = 1, b = 2 }]")
    separator <- Gen.oneOf(" = ", " : ")
  } yield key + separator + value

  property("the bounded report preserves the original findings and their source order") {
    forAll(Gen.choose(1, 35).flatMap(n => Gen.listOfN(n, statement)), Gen.oneOf("\n", ", ")) { (lines, separator) =>
      val source = lines.mkString(separator)
      DuplicateReport.outline(source) match {
        case Right(outline) => assertEquals(DuplicateReport.findings(source), Right(reference(outline.definitions)))
        case Left(refusal)  => fail(refusal.reason)
      }
    }
  }
}
