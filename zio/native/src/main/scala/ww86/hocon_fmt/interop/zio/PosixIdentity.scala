package ww86.hocon_fmt.interop.zio

import java.nio.file.Path

import scala.scalanative.posix.sys.statOps.statOps
import scala.scalanative.posix.sys.{stat as sys}
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.util.control.NonFatal

/** Owner, group and mode bits through the C library: the javalib exposes only the nine permission
  * bits, so a staged copy would lose setuid, setgid and sticky.
  */
private[zio] object PosixIdentity {

  def read(path: Path): Option[FileIdentity] =
    try
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
    catch { case NonFatal(_) => None }

  /** Give the staged file the original's identity, then read it back. False when this process may
    * not, which is the caller's signal to write in place.
    */
  def keep(staged: Path, target: FileIdentity): Boolean =
    adopt(staged, target) && read(staged).contains(target)

  // The owner first: chown can clear setgid, which the chmod then restores.
  private def adopt(staged: Path, target: FileIdentity): Boolean =
    try
      Zone.acquire { implicit zone =>
        val path  = toCString(staged.toString)
        val owner = unistd.chown(path, target.owner.toUInt, target.group.toUInt)
        val mode  = sys.chmod(path, target.mode.toUInt)
        owner == 0 && mode == 0
      }
    catch { case NonFatal(_) => false }
}
