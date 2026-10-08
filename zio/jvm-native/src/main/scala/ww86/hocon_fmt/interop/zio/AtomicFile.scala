package ww86.hocon_fmt.interop.zio

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import _root_.zio.{IO, ZIO}

private[zio] object AtomicFile {

  /** Replaces `target` with `text` only when a staged copy can be given the file's identity — its
    * owner, group and every mode bit, read back to prove it — and both the file and its directory
    * can be written. Otherwise the text is written in place, which loses the crash-atomicity of
    * the rename but keeps the file the same inode, as the CLI did before the adapters.
    */
  def write(target: Path, text: String): IO[IOException, Unit] =
    // Keeping staging and cleanup in one blocking operation prevents interruption racing a writer.
    BlockingIo(replaceWithStaged(target, text)).flatMap {
      case true  => ZIO.unit
      case false => BlockingIo(writeInPlace(target, text)).unit
    }.uninterruptible

  // True when the target was replaced by a staged copy carrying its identity. A file or directory
  // that cannot be written is not replaced: the write in place then fails the way it always did,
  // rather than a read-only file changing under its owner.
  private def replaceWithStaged(target: Path, text: String): Boolean =
    (PosixIdentity.read(target), Option(target.getParent)) match {
      case (Some(identity), Some(parent)) if Files.isWritable(target) && Files.isWritable(parent) =>
        stage(parent, target, text, identity)
      case _ => false
    }

  // Both paths are on one filesystem, so the rename is atomic.
  private def stage(parent: Path, target: Path, text: String, identity: FileIdentity): Boolean = {
    val temporary = Files.createTempFile(parent, ".hocon-fmt-", ".tmp")
    try {
      val _    = Files.write(temporary, text.getBytes(UTF_8))
      val kept = PosixIdentity.keep(temporary, identity)
      if kept then {
        val _ = Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      }
      kept
    } finally {
      val _ = Files.deleteIfExists(temporary)
    }
  }

  // Truncate and write, as the CLI did before this adapter. The formatted text was verified
  // before either write, so a refusal never reaches here.
  private def writeInPlace(target: Path, text: String): Path =
    Files.write(target, text.getBytes(UTF_8))
}
