package eu.ww86.hoconfmt.interop.zio

import scala.concurrent.ExecutionContext.Implicits.global
import _root_.zio.{Runtime, Unsafe}
import eu.ww86.hoconfmt.{HoconFormatter, Refusal, Verdict}

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
      val sources = List("a : 1", "a = 1\n", "a={")
      Runtime.default.unsafe.runToFuture(_root_.zio.ZIO.foreach(sources)(ZioFormatter.verdict)).map { results =>
        assertEquals(results, sources.map(Verdict.of))
      }
    }
  }

  // The name is what a refusal reports as the place a parse tripped, and what rules a file named
  // as another format out.
  test("a name reaches the refusal") {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.runToFuture(ZioFormatter.format("a={", "app.conf").either).map { result =>
        assertEquals(result, HoconFormatter.format("a={", "app.conf"))
        assert(result.left.exists(_.reason.startsWith("not valid HOCON: app.conf:")), result)
      }
    }
  }

  test("a name rules another format out of the verdict") {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.runToFuture(ZioFormatter.verdict("a: 1\n", "application.json")).map { result =>
        assertEquals(result, Verdict.Refused(Refusal.OtherFormat("JSON")))
      }
    }
  }
}
