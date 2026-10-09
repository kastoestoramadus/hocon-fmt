package eu.ww86.hoconfmt

import _root_.cats.effect.IO

/** Node reads stdin as a stream, and writes UTF-8 whatever the locale. */
private[hoconfmt] object StdStreams {
  def readStdin: IO[Array[Byte]] = fs2.io.stdin[IO](4096).compile.to(Array)

  def writeStdout(text: String): IO[Unit] = IO.print(text)
}
