package ww86.hoconfmt.interop.zio

import java.nio.file.Files as NioFiles
import java.nio.file.Path
import scala.util.control.NonFatal

/** Owner, group and mode bits through the JVM's unix view, which Windows does not have: there a
  * file has no such identity to keep, and the caller writes in place instead.
  */
private[zio] object PosixIdentity {

  def read(path: Path): Option[FileIdentity] =
    try {
      val attributes = NioFiles.readAttributes(path, "unix:uid,gid,mode")
      (attributes.get("uid"), attributes.get("gid"), attributes.get("mode")) match {
        case (owner: Integer, group: Integer, mode: Integer) =>
          Some(FileIdentity(owner.intValue, group.intValue, mode.intValue & FileIdentity.modeBits))
        case _ => None
      }
    } catch { case NonFatal(_) => None }

  /** Give the staged file the original's identity, then read it back. False when this process may
    * not, which is the caller's signal to write in place.
    */
  def keep(staged: Path, target: FileIdentity): Boolean =
    adopt(staged, target) && read(staged).contains(target)

  // The owner first: chown can clear setgid, which the chmod then restores.
  private def adopt(staged: Path, target: FileIdentity): Boolean =
    try {
      val _ = NioFiles.setAttribute(staged, "unix:uid", Int.box(target.owner))
      val _ = NioFiles.setAttribute(staged, "unix:gid", Int.box(target.group))
      val _ = NioFiles.setAttribute(staged, "unix:mode", Int.box(target.mode))
      true
    } catch { case NonFatal(_) => false }
}
