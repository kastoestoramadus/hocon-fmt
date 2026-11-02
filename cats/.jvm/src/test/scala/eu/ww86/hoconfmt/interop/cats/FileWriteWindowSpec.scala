package eu.ww86.hoconfmt.interop.cats

import cats.effect.{Async, IO}
import fs2.io.file.{Files, Path}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean

/** The window between the unchanged-bytes check and the in-place write, reached deterministically:
  * the formatter runs on a wrapped Async that swaps the path entry — the interleaving of the cliJS
  * CI failure (run 41228182216) — at a marked point inside the write path, after the staging checks
  * and before the in-place write. No sleep, no loop: the swap is placed, not raced.
  *
  * The mark is the staging identity read (AtomicFiles.identity), the last file operation between
  * the guard's read and the in-place write. Staging declines around it (the directory is 0555, and
  * the hook restores 0755 so the swap itself may write the directory), so the next thing the
  * formatter does to the target is the write.
  */
class FileWriteWindowSpec extends munit.CatsEffectSuite {

  val tmp = ResourceFunFixture(Files[IO].tempDirectory)

  def writeUtf8(file: Path, text: String): IO[Unit] =
    fs2.Stream.emit(text).through(Files[IO].writeUtf8(file)).compile.drain

  def textOf(file: Path): IO[String] =
    Files[IO].readAll(file).compile.to(Array).map(String(_, UTF_8))

  /** Async[IO] delegating to IO's own, running `hook` once after the staging identity read
    * completes — inside the check→write window. Everything else behaves as IO.
    */
  final private class SwapAfterStaging(hook: IO[Unit]) extends Async[IO] {
    private val delegate = Async[IO]
    private val fired    = new AtomicBoolean(false)

    private def maybeFire[A](result: A): IO[Unit] = result match {
      case Some(_: FileIdentity) if fired.compareAndSet(false, true) => hook
      case _                                                         => IO.unit
    }

    def pure[A](a: A): IO[A]                                                     = delegate.pure(a)
    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B]                           = delegate.flatMap(fa)(f)
    def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B]                    = delegate.tailRecM(a)(f)
    def raiseError[A](e: Throwable): IO[A]                                       = delegate.raiseError(e)
    def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A]              = delegate.handleErrorWith(fa)(f)
    def uncancelable[A](body: cats.effect.Poll[IO] => IO[A]): IO[A]              = delegate.uncancelable(body)
    def canceled: IO[Unit]                                                       = delegate.canceled
    def monotonic: IO[scala.concurrent.duration.FiniteDuration] = delegate.monotonic
    def realTime: IO[scala.concurrent.duration.FiniteDuration]   = delegate.realTime
    def suspend[A](hint: cats.effect.Sync.Type)(thunk: => A): IO[A] = delegate.suspend(hint)(thunk)
    def executionContext: IO[scala.concurrent.ExecutionContext]     = delegate.executionContext
    def cont[K, R](body: cats.effect.Cont[IO, K, R]): IO[R]         = delegate.cont(body)
    def evalOn[A](fa: IO[A], ec: scala.concurrent.ExecutionContext): IO[A] = delegate.evalOn(fa, ec)
    def deferred[A]: IO[cats.effect.kernel.Deferred[IO, A]]                = delegate.deferred
    def ref[A](a: A): IO[cats.effect.kernel.Ref[IO, A]]                    = delegate.ref(a)
    def cede: IO[Unit]                                                      = delegate.cede
    def start[A](fa: IO[A]): IO[cats.effect.kernel.Fiber[IO, Throwable, A]] = delegate.start(fa)
    def forceR[A, B](fa: IO[A])(fb: IO[B]): IO[B]                           = delegate.forceR(fa)(fb)
    def onCancel[A](fa: IO[A], fin: IO[Unit]): IO[A]                        = delegate.onCancel(fa, fin)

    protected def sleep(time: scala.concurrent.duration.FiniteDuration): IO[Unit] =
      delegate.sleep(time)

    // The one interception: staging's identity read is a blocking call returning the identity.
    override def blocking[A](thunk: => A): IO[A] = delegate.blocking(thunk).flatTap(maybeFire)
  }

  tmp.test("a symlink swapped inside the check-to-write window does not clobber its target") { dir =>
    val real      = Files.forIO
    val file      = dir / "race.conf"
    val victim    = dir / "victim.conf"
    val moved     = dir / "race.moved"
    val formatter = FileFormatter[IO](
      using new SwapAfterStaging(
        TestPosix.chmod(dir, 0x1ed) *> // 0755 again, so the swap may write the directory
          real.move(file, moved) *>
          real.createSymbolicLink(file, victim)
      ),
      real
    )
    val run = for {
      _      <- writeUtf8(file, "a : 1\n")
      _      <- writeUtf8(victim, "secret = keep\n")
      _      <- TestPosix.chmod(dir, 0x155) // 0555: staging declines, so the write is in place
      out    <- formatter.format(file).attempt
      after  <- textOf(victim)
      landed <- textOf(moved)
    } yield (out, after, landed)
    run.guarantee(TestPosix.chmod(dir, 0x1ed).attempt.void).map { case (_, after, _) =>
      // The victim the swap pointed at keeps its bytes whatever the run reported; the formatted
      // text either landed on the file the check saw or the run refused to write.
      assertEquals(after, "secret = keep\n")
    }
  }
}
