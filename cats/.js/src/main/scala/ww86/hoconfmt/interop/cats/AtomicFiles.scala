package ww86.hoconfmt.interop.cats

import _root_.cats.effect.Sync
import _root_.cats.syntax.all.*
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.util.control.NonFatal

/** Owner, group and mode bits through Node's fs: fs2's permission API carries neither owner nor
  * group, and only the nine permission bits, so a staged copy would lose setuid, setgid and sticky.
  */
private[interop] object AtomicFiles {

  @js.native
  @JSImport("fs", JSImport.Namespace)
  private object NodeFs extends js.Object {
    def statSync(path: String): js.Dynamic                = js.native
    def chmodSync(path: String, mode: Int): Unit          = js.native
    def chownSync(path: String, uid: Int, gid: Int): Unit = js.native
  }

  def identity[F[_]: Sync](path: Path): F[Option[FileIdentity]] =
    Sync[F].delay {
      try {
        val attributes = NodeFs.statSync(path.toString)
        for {
          owner <- number(attributes, "uid")
          group <- number(attributes, "gid")
          mode  <- number(attributes, "mode")
        } yield FileIdentity(owner, group, mode & FileIdentity.modeBits)
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
    Sync[F].delay {
      try {
        NodeFs.chownSync(staged.toString, target.owner, target.group)
        NodeFs.chmodSync(staged.toString, target.mode)
        true
      } catch { case NonFatal(_) => false }
    }

  // The only cast in this file: it follows the typeOf check that makes it safe.
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def number(attributes: js.Dynamic, name: String): Option[Int] = {
    val value = attributes.selectDynamic(name)
    if js.typeOf(value) == "number" then Some(value.asInstanceOf[Double].toInt) else None
  }

  /** Replace the target with the staged file: fs2 on Node renames, which replaces a sibling
    * atomically, and has no separate atomic-move flag to ask for.
    */
  def move[F[_]](files: Files[F], staged: Path, target: Path): F[Unit] =
    files.move(staged, target, CopyFlags(CopyFlag.ReplaceExisting))
}
