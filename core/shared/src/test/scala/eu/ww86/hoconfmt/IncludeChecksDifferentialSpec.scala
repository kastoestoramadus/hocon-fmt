package eu.ww86.hoconfmt

import org.ekrich.config.ConfigFactory
import scala.util.Try

object IncludeChecksDifferentialInputs {
  val targets: List[String] = List(
    "\"a.conf\"",
    "required(file(\"a b.conf\"))",
    "file(\"a\\\\b.conf\")",
    "url(\"https://example.org/a.conf\")",
    "classpath(\"a.conf\")"
  )
  val bodies: List[String] = List(
    "a=1\nINCLUDE\nb=2",
    "INCLUDE, q=0, q.a=1, q {}",
    "q=0, q.a=1, INCLUDE, q {}",
    "x.a=5\nINCLUDE\nx {}",
    "INCLUDE\no=3\no.c=7",
    "INCLUDE\np {}, p=3",
    "INCLUDE\np=${?optional}\np {}",
    "q=1\nINCLUDE\nq=2\nINCLUDE",
    "include_key=include\n# include \"ignored\"\nINCLUDE\ns=\"include file(x)\"",
    "INCLUDE\na=[{ INCLUDE, x=1 }, { INCLUDE, x=2 }]",
    "INCLUDE\na { INCLUDE\nb { INCLUDE\nx=1 } }"
  )
  val adversarial: List[String] = (for {
    target  <- targets
    body    <- bodies
    wrap    <- List[String => String](identity, s => s"app {\n$s\n}", s => s"rows=[0] [{\n$s\n}]", s => s"app={} {\n$s\n}")
    newline <- List("\n", "\r\n")
  } yield wrap(body.replace("INCLUDE", s"include $target")).replace("\n", newline)) ++ List(
    "a=[{ include \"x\"\nx=1 }]\na=[{ include \"y\"\nx=2 }]",
    "include \"first\"\nx=0\ninclude \"second\"\nx.a=1\ninclude \"third\"\nx {}"
  )
  def large(n: Int): String = (0 until n).map(i => s"include \"part$i.conf\"\nkey$i=$i\n").mkString
}

trait IncludeChecksDifferentialChecks extends munit.Assertions {
  def checkIncludeChecks(source: String): Unit = {
    val masked = IncludeMasking.mask(source)
    HoconText
      .checkNesting(source)
      .toOption
      .flatMap { _ =>
        Try(ConfigFactory.parseString(masked.text, HoconFormatter.parseOptions).root).toOption
      }
      .foreach { root =>
        Try(root.render(HoconFormatter.renderOptions)).toOption.foreach { rendered =>
          assertEquals(
            IncludeOrder.moved(source, rendered, masked.originals),
            IncludeOrderOracle.moved(source, rendered, masked.originals),
            source
          )
          // Changed renderings exercise non-empty results and the source-order choice between includes.
          val reversed = IncludeMasking.mask(source.linesIterator.toList.reverse.mkString("\n")).text
          assertEquals(
            IncludeOrder.moved(source, reversed, masked.originals),
            IncludeOrderOracle.moved(source, reversed, masked.originals),
            source
          )
        }
        assertEquals(
          IncludeShadow.shadowed(masked.text, masked.originals, root),
          IncludeShadowOracle.shadowed(masked.text, masked.originals, root),
          source
        )
      }
    assertEquals(HoconFormatter.format(source), IncludeChecksOldFormatter.format(source), source)
  }
}

class IncludeChecksDifferentialSpec extends munit.FunSuite with IncludeChecksDifferentialChecks {
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(10, "minutes")
  test("include checks preserve exact refusals and outputs on adversarial scopes, paths and lines") {
    IncludeChecksDifferentialInputs.adversarial.foreach(checkIncludeChecks)
    println(s"include differential: ${IncludeChecksDifferentialInputs.adversarial.size} adversarial inputs")
  }
  test("include checks agree on deterministic generated inputs") {
    PlaceholderFieldDifferentialInputs.generated.foreach(checkIncludeChecks)
    println("include differential: 1000 generated inputs")
  }
  test("include checks agree through 2000 includes") {
    List(100, 250, 500, 1000, 2000).foreach(n => checkIncludeChecks(IncludeChecksDifferentialInputs.large(n)))
  }
}
