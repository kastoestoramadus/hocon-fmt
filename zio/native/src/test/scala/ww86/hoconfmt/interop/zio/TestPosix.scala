package ww86.hoconfmt.interop.zio

import java.nio.file.Path

import scala.scalanative.posix.sys.statOps.statOps
import scala.scalanative.posix.sys.{stat as sys}
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Owner, group and every mode bit, setgid included, which the javalib's nine-bit permissions API
  * misses.
  */
object TestPosix {
  final case class Stat(owner: Int, group: Int, mode: Int) derives CanEqual

  def stat(path: Path): Stat = Zone.acquire { implicit zone =>
    val buffer = stackalloc[sys.stat]()
    if sys.stat(toCString(path.toString), buffer) == 0 then
      Stat(buffer.st_uid.toInt, buffer.st_gid.toInt, buffer.st_mode.toInt & 0xfff)
    else throw new IllegalStateException(s"cannot stat $path")
  }

  def chmod(path: Path, mode: Int): Unit = Zone.acquire { implicit zone =>
    if sys.chmod(toCString(path.toString), (mode & 0xfff).toUInt) != 0 then
      throw new IllegalStateException(s"cannot chmod $path")
  }

  def chgrp(path: Path, group: Int): Unit = Zone.acquire { implicit zone =>
    if unistd.chown(toCString(path.toString), (-1).toUInt, group.toUInt) != 0 then
      throw new IllegalStateException(s"cannot chgrp $path")
  }

  /** The file's inode, which a rename changes and a write in place does not. */
  def ino(path: Path): Long = Zone.acquire { implicit zone =>
    val buffer = stackalloc[sys.stat]()
    if sys.stat(toCString(path.toString), buffer) == 0 then buffer.st_ino.toLong
    else throw new IllegalStateException(s"cannot stat $path")
  }

  /** How many directory entries point at the file's inode. */
  def nlink(path: Path): Long = Zone.acquire { implicit zone =>
    val buffer = stackalloc[sys.stat]()
    if sys.stat(toCString(path.toString), buffer) == 0 then buffer.st_nlink.toLong
    else throw new IllegalStateException(s"cannot stat $path")
  }

  /** A group this process may give a file other than the one `path` is in, where it has one. */
  def otherGroup(path: Path): Option[Int] = {
    val current = stat(path)
    Zone.acquire { implicit zone =>
      val size   = 64
      val buffer = stackalloc[sys.gid_t](size)
      val count  = unistd.getgroups(size, buffer)
      (0 until math.max(count, 0)).toList.map(index => buffer(index).toInt).find(_ != current.group)
    }
  }
}
