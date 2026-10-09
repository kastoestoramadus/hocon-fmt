package ww86.hoconfmt.interop.cats

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.charset.StandardCharsets.UTF_8

import _root_.cats.effect.{Deferred, IO}
import fs2.{Pipe, Stream}
import fs2.io.file.{Files, Path}

/** A real filesystem with only the staged write intercepted: exercise failure after bytes reach disk. */
class FileFormatterFailureSpec extends munit.CatsEffectSuite {
  val actual = Files[IO]
  val tmp    = ResourceFunFixture(actual.tempDirectory)
  val source = "a=1"

  def write(file: Path, text: String): IO[Unit] =
    Stream.emit(text).through(actual.writeUtf8(file)).compile.drain

  def interruptedFiles(afterPartialWrite: IO[Unit]): Files[IO] = {
    val handler = new InvocationHandler {
      override def invoke(proxy: Object, method: Method, arguments: Array[Object] | Null): Object | Null = {
        val args = Option(arguments).getOrElse(Array.empty[Object])
        if (method.getName == "writeUtf8") {
          val path                            = args.toList.collectFirst { case value: Path => value }.getOrElse(fail("missing write path"))
          val pipe: Pipe[IO, String, Nothing] = _.flatMap { content =>
            Stream.emit(content.take(2)).through(actual.writeUtf8(path)) ++ Stream.exec(afterPartialWrite)
          }
          pipe
        } else method.invoke(actual, args*)
      }
    }
    Proxy
      .newProxyInstance(classOf[Files[IO]].getClassLoader, Array(classOf[Files[IO]]), handler)
      .asInstanceOf[Files[IO]]
  }

  tmp.test("a failed partial write preserves the original and cleans up staging") { dir =>
    val error     = new java.io.IOException("write interrupted")
    val formatter = new FileFormatter[IO](using IO.asyncForIO, interruptedFiles(IO.raiseError(error)))
    val file      = dir / "a.conf"
    for {
      _       <- write(file, source)
      result  <- formatter.format(file).attempt
      bytes   <- actual.readAll(file).compile.toList
      entries <- actual.list(dir).compile.toList
    } yield {
      result match {
        case Left(error)    => assertEquals(error.getMessage, "write interrupted")
        case Right(outcome) => fail(s"expected IO failure, got $outcome")
      }
      assertEquals(bytes, source.getBytes(UTF_8).toList)
      assertEquals(entries, List(file))
    }
  }

  tmp.test("cancellation after a partial write preserves the original and cleans up staging") { dir =>
    val file = dir / "a.conf"
    for {
      started  <- Deferred[IO, Unit]
      formatter = new FileFormatter[IO](using IO.asyncForIO, interruptedFiles(started.complete(()).void *> IO.never))
      _        <- write(file, source)
      fiber    <- formatter.format(file).start
      _        <- started.get
      _        <- fiber.cancel
      bytes    <- actual.readAll(file).compile.toList
      entries  <- actual.list(dir).compile.toList
    } yield {
      assertEquals(bytes, source.getBytes(UTF_8).toList)
      assertEquals(entries, List(file))
    }
  }
}
