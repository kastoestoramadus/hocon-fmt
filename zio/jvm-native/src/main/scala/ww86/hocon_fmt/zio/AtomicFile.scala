package ww86.hocon_fmt.zio

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.nio.file.attribute.PosixFileAttributeView
import _root_.zio.{IO, ZIO}

private[zio] object AtomicFile {
  def write(target: Path, text: String): IO[IOException, Unit] =
    // Keeping staging and cleanup in one blocking operation prevents interruption racing a writer.
    ZIO.attemptBlockingIO {
      val temporary = Files.createTempFile(target.toAbsolutePath().getParent, ".hocon-fmt-", ".tmp")
      try {
        val _ = Files.write(temporary, text.getBytes(UTF_8))
        Option(Files.getFileAttributeView(target, classOf[PosixFileAttributeView])).foreach { view =>
          val _ = Files.setPosixFilePermissions(temporary, view.readAttributes().permissions())
        }
        // No non-atomic fallback: an unsupported filesystem leaves the original intact.
        val _ = Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } finally {
        val _ = Files.deleteIfExists(temporary)
      }
    }.uninterruptible
}
