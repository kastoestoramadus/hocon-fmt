package consumer

import ww86.hocon_fmt.*
import ww86.hocon_fmt.interop.cats.{FileFormatter, FormatOutcome}

import cats.effect.IO
import fs2.Stream
import fs2.io.file.{Files, Path}

/** A consumer's file, not a test of behaviour: it imports the core with a wildcard and the root
  * `cats` package in the same scope, which is what the cats adapter itself always needs. It only
  * compiles while the adapter's package does not shadow `cats`, so the compile is the assertion.
  */
class AdapterInteropSpec extends munit.CatsEffectSuite {
  val formatter = FileFormatter[IO]
  val tmp       = ResourceFunFixture(Files[IO].tempDirectory)

  def write(file: Path): IO[Unit] =
    Stream.emit("a=1").through(Files[IO].writeUtf8(file)).compile.drain

  tmp.test("a consumer reaches the core and the adapter in one file") { dir =>
    val file = dir / "a.conf"
    for {
      _       <- write(file)
      outcome <- formatter.format(file)
      verdict <- formatter.verdict(file)
    } yield {
      assertEquals(outcome, FormatOutcome.Formatted)
      assertEquals(verdict, Verdict.AlreadyFormatted)
      assertEquals(Verdict.of(Array(0xff.toByte)), Verdict.Refused(Refusal.NotUtf8))
    }
  }
}
