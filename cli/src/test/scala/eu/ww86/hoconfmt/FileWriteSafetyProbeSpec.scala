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
    fs2.io.file
      .Files[IO]
      .readAll(file)
      .compile
      .to(Array)
      .map(bytes => String(bytes, java.nio.charset.StandardCharsets.UTF_8))

  def rewrite(files: fs2.io.file.Path*): IO[CmdApi.Run] =
    CmdApi
      .examineAll(Arguments(files.toList, checkOnly = false, config = None, style = StyleOverrides.none))
      .map(_.fold(e => fail(e), identity))

  // A key followed by nothing but blank lines: sconfig queues one token per newline, so
  // formatting this shape takes seconds of CPU and RAM proportional to the blank lines, while
  // the file stays a tiny HOCON document the work budget has no objection to.
  def flood(blankLines: Int): String = "a=1\n" + "\n" * blankLines

  // Single-segment keys the blank-line budget has no objection to (no line is blank) and the
  // path-work budget accepts (10000 one-segment statements under the 300000 budget), whose
  // formatting still spends seconds in the parser, so a fixed sleep lands inside the format
  // phase, after the file has been read. The 3 MiB blank-line flood the two races used first is
  // refused by the blank-line budget in about a second, which is what made those races moot.
  // 10000, not 100000: at 100000 the format phase alone runs ~30 s on the JVM and ~90-110 s on
  // Scala.js under load (probe 1173, 2026-10-31), past munit's 30 s per-test default, so both
  // races failed by timeout. At 10000 this suite finishes in 2.8 s (JVM) and 5.1 s (JS), and the
  // 600 ms sleep still lands inside the format phase: with the re-read guard reverted both races
  // still fail (JVM 3.3 s / 0.8 s, JS 0.8 s / 0.6 s). Native: the author measured 7.2 s at
  // 10000 against >180 s at 100000; not re-run in this review.
  def manyKeys(lines: Int): String = (0 until lines).map(i => s"k$i=$i\n").mkString

  /** The outcome kinds that own up to not having written: the run could not write the target,
    * could not read it, or the formatter refused it. A formatting verdict (Rewritten,
    * AlreadyFormatted, NeedsFormatting) is a claim about the text, not a refusal.
    */
  def reportsRefusal(result: CmdApi.Result): Boolean = result match {
    case CmdApi.Result.Unwritable(_) | CmdApi.Result.Unreadable(_) | CmdApi.Result.Unformattable(_) => true
    case _                                                                                          => false
  }

  tmp.test("a concurrent edit made between the read and the write is not silently lost") { dir =>
    for {
      file  <- write(dir, "race.conf", manyKeys(10000))
      run   <- rewrite(file).start
      _     <- IO.sleep(600.millis)
      _     <- write(dir, "race.conf", "concurrent = edit\n")
      _     <- run.joinWithNever
      after <- textOf(file)
    } yield assert(clue(after == "concurrent = edit\n"))
  }

  tmp.test("a run does not die on a file of blank lines") { dir =>
    // 5 MiB: under a bounded heap (the shipped Native binary starts at 512m) sconfig's
    // tokenizer queues one token per newline and the run dies with an unhandled
    // OutOfMemoryError instead of reporting an outcome for this file. Measured OOM at
    // -Xmx512m on 5 MiB: 480-hocon-fmt-file-write-safety-probe/evidence/d10-oome.log.
    for {
      file <- write(dir, "flood.conf", flood(5 * 1024 * 1024))
      run  <- rewrite(file)
    } yield assert(clue(run.outcomes.size == 1))
  }

  tmp.test("a symlink swapped onto the input path mid-format ends in no silent outcome") { dir =>
    // The 480 pre-review saw a third outcome beside the clobber: with the path entry swapped to
    // a symlink during the format phase, the run reported success while the formatted text was
    // written nowhere (exit 0, victim intact, no file holding the text — race/swap-runs.log).
    // Either the swapped-in file keeps its bytes — a run must not write a file never named on
    // the command line — or the run reports a refusal; a victim whose bytes changed while no
    // outcome owns up to the write — reported as a rewrite, a formatting verdict, or nothing
    // at all — is the silent overwrite this test exists to fail on.
    for {
      victim   <- write(dir, "victim.conf", "secret = keep\n")
      file     <- write(dir, "race.conf", manyKeys(10000))
      run      <- rewrite(file).start
      _        <- IO.sleep(600.millis)
      _        <- Files[IO].move(file, dir / "race.flood")
      _        <- Files[IO].createSymbolicLink(file, victim)
      finished <- run.joinWithNever
      after    <- textOf(victim)
      kept      = after == "secret = keep\n"
      refused   = finished.outcomes.exists(o => reportsRefusal(o.result))
    } yield assert(
      clue(kept || refused),
      s"victim now [$after] after ${finished.outcomes.map(_.result.toString).mkString}"
    )
  }

}
