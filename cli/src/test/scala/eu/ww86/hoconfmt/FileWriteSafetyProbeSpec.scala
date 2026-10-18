package eu.ww86.hoconfmt

import _root_.cats.effect.IO
import fs2.io.file.Files

import scala.concurrent.duration.*

import eu.ww86.hoconfmt.CmdApi.Arguments

/** Failing tests from the file-write safety probe (task 480): each names a way the product
  * promise — a file is changed only with its formatted text, and a run reports what it did —
  * is broken on real files. They are red on purpose; the fixes are proposals in the probe's
  * handoff, not changes here.
  */
class FileWriteSafetyProbeSpec extends munit.CatsEffectSuite {

  val tmp = ResourceFunFixture(Files[IO].tempDirectory)

  def write(dir: fs2.io.file.Path, name: String, text: String): IO[fs2.io.file.Path] =
    fs2.Stream.emit(text).through(fs2.io.file.Files[IO].writeUtf8(dir / name)).compile.drain.as(dir / name)

  def textOf(file: fs2.io.file.Path): IO[String] =
    fs2.io.file.Files[IO].readAll(file).compile.to(Array).map(bytes => String(bytes, java.nio.charset.StandardCharsets.UTF_8))

  def rewrite(files: fs2.io.file.Path*): IO[CmdApi.Run] =
    CmdApi
      .examineAll(Arguments(files.toList, checkOnly = false, config = None, style = StyleOverrides.none))
      .map(_.fold(e => fail(e), identity))

  // A key followed by a run of blank lines: sconfig queues one token per newline, so formatting
  // this shape takes seconds of CPU and RAM proportional to the blank lines, while the file stays
  // a tiny HOCON document the work budget has no objection to.
  def flood(blankLines: Int): String = "a=1\n" + "\n" * blankLines

  tmp.test("a concurrent edit made between the read and the write is not silently lost") { dir =>
      // 3 MiB of blank lines: formatting runs for seconds, so a fixed sleep lands inside the
      // format phase on every platform, after the file has been read.
      for {
        file  <- write(dir, "race.conf", flood(3 * 1024 * 1024))
        run   <- rewrite(file).start
        _     <- IO.sleep(600.millis)
        _     <- write(dir, "race.conf", "concurrent = edit\n")
        _     <- run.joinWithNever
        after <- textOf(file)
      } yield assert(clue(after == "concurrent = edit\n"))
  }

}
