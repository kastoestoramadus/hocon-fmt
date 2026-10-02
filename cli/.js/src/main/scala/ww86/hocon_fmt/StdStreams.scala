package ww86.hocon_fmt

import cats.effect.IO

/** Node reads stdin as a stream, and writes UTF-8 whatever the locale. */
private[hocon_fmt] object StdStreams {
  def readStdin: IO[Array[Byte]] = fs2.io.stdin[IO](4096).compile.to(Array)

  def writeStdout(text: String): IO[Unit] = IO.print(text)
}
