package ww86.hocon_fmt.zio

import scala.concurrent.ExecutionContext.Implicits.global
import _root_.zio.{Runtime, Unsafe}
import ww86.hocon_fmt.{HoconFormatter, Verdict}

class ZioFormatterSpec extends munit.FunSuite {
  test("format lifts successful text into ZIO") {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.runToFuture(ZioFormatter.format("a=1").either).map { output =>
        assertEquals(output, HoconFormatter.format("a=1"))
      }
    }
  }

  test("format puts the original refusal in the typed error channel") {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.runToFuture(ZioFormatter.format("a={").either).map { result =>
        assertEquals(result, HoconFormatter.format("a={"))
      }
    }
  }

  test("verdict preserves all core decisions") {
    Unsafe.unsafe { implicit unsafe =>
      val sources = List("a=1", "a: 1\n", "a={")
      Runtime.default.unsafe.runToFuture(_root_.zio.ZIO.foreach(sources)(ZioFormatter.verdict)).map { results =>
        assertEquals(results, sources.map(Verdict.of))
      }
    }
  }
}
