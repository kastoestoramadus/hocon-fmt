package ww86.hocon_fmt

import java.nio.charset.StandardCharsets.UTF_8

import cats.effect.IO

/** The standard streams as bytes, through blocking `java.io`.
  *
  * Not fs2-io: on Scala Native `fs2.io.stdin` and `stdout` register the descriptor with epoll, which fails with EPERM when
  * it is a regular file, so `--stdin < in.conf > out.conf` would not work. `IO.print` on the JVM encodes with the locale,
  * so `LC_ALL=C` turns every non-ASCII character into '?'. Writing the bytes avoids both.
  */
private[hocon_fmt] object StdStreams {
  def readStdin: IO[Array[Byte]] = IO.blocking(System.in.readAllBytes())

  def writeStdout(text: String): IO[Unit] =
    IO.blocking {
      val bytes = text.getBytes(UTF_8)
      System.out.write(bytes, 0, bytes.length)
      System.out.flush()
    }
}
