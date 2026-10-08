package ww86.hocon_fmt.interop.cats

import _root_.cats.effect.Sync
import _root_.cats.syntax.all.*
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}

import scala.scalanative.posix.sys.statOps.statOps
import scala.scalanative.posix.sys.{stat as sys}
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.util.control.NonFatal

/** Owner, group and mode bits through the C library: the javalib exposes only the nine permission
  * bits, so a staged copy would lose setuid, setgid and sticky.
  */
private[interop] object AtomicFiles {

  def identity[F[_]: Sync](path: Path): F[Option[FileIdentity]] =
    Sync[F].blocking {
      try {
        Zone.acquire { implicit zone =>
          val buffer = stackalloc[sys.stat]()
          if sys.stat(toCString(path.toString), buffer) == 0 then
            Some(
              FileIdentity(
                buffer.st_uid.toInt,
                buffer.st_gid.toInt,
                buffer.st_mode.toInt & FileIdentity.modeBits
              )
            )
          else None
        }
      } catch { case NonFatal(_) => None }
    }

  /** Give the staged file the original's owner, group and mode bits, then read them back. False
    * when this process may not, which is the caller's signal to write in place.
    */
  def keepIdentity[F[_]: Sync](staged: Path, target: FileIdentity): F[Boolean] =
    adopt(staged, target).flatMap {
      case true  => identity(staged).map(_.contains(target))
      case false => false.pure[F]
    }

  // The owner first: chown can clear setgid, which the chmod then restores.
  private def adopt[F[_]: Sync](staged: Path, target: FileIdentity): F[Boolean] =
    Sync[F].blocking {
      Zone.acquire { implicit zone =>
        val path  = toCString(staged.toString)
        val owner = unistd.chown(path, target.owner.toUInt, target.group.toUInt)
        val mode  = sys.chmod(path, target.mode.toUInt)
        owner == 0 && mode == 0
      }
    }

  /** Replace the target with the staged file; both are on one filesystem, so the rename is atomic. */
  def move[F[_]](files: Files[F], staged: Path, target: Path): F[Unit] =
    files.move(staged, target, CopyFlags(CopyFlag.AtomicMove, CopyFlag.ReplaceExisting))
}
