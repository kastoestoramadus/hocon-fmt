package eu.ww86.hoconfmt.interop.cats

import java.nio.file.Files as NioFiles

import _root_.cats.effect.Sync
import _root_.cats.syntax.all.*
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}

import scala.util.control.NonFatal

/** Owner, group and mode bits through the JVM's unix view, which Windows does not have: there a
  * file has no such identity to keep, and the caller writes in place instead.
  */
private[interop] object AtomicFiles {

  def identity[F[_]: Sync](path: Path): F[Option[FileIdentity]] =
    Sync[F].blocking {
      try {
        val attributes = NioFiles.readAttributes(path.toNioPath, "unix:uid,gid,mode")
        (attributes.get("uid"), attributes.get("gid"), attributes.get("mode")) match {
          case (owner: Integer, group: Integer, mode: Integer) =>
            Some(FileIdentity(owner.intValue, group.intValue, mode.intValue & FileIdentity.modeBits))
          case _ => None
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

  private def adopt[F[_]: Sync](staged: Path, target: FileIdentity): F[Boolean] =
    Sync[F].blocking {
      try {
        NioFiles.setAttribute(staged.toNioPath, "unix:uid", Int.box(target.owner))
        NioFiles.setAttribute(staged.toNioPath, "unix:gid", Int.box(target.group))
        NioFiles.setAttribute(staged.toNioPath, "unix:mode", Int.box(target.mode))
        true
      } catch { case NonFatal(_) => false }
    }

  /** Replace the target with the staged file; both are on one filesystem, so the rename is atomic. */
  def move[F[_]](files: Files[F], staged: Path, target: Path): F[Unit] =
    files.move(staged, target, CopyFlags(CopyFlag.AtomicMove, CopyFlag.ReplaceExisting))
}
