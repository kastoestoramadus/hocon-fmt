package eu.ww86.hoconfmt.interop.zio

import java.io.IOException
import scala.util.control.NonFatal
import _root_.zio.{IO, ZIO}

/** One blocking `java.nio` call, its failure typed instead of a defect.
  *
  * The JDK's file providers throw unchecked exceptions as well as `IOException`: a path on a closed
  * ZIP filesystem raises `ClosedFileSystemException`, an unknown scheme `ProviderNotFoundException`.
  * Unhandled, either kills the fiber, and a streamed check loses the outcomes of the paths after it
  * instead of reporting that one file. The original exception stays as the cause, `IOException`
  * being the only failure to access a file the adapter's error type carries.
  */
private[zio] object BlockingIo {
  def apply[A](operation: => A): IO[IOException, A] =
    ZIO.attemptBlocking(operation).mapError {
      case io: IOException => io
      case NonFatal(other) => new IOException(other)
    }
}
