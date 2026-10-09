package ww86.hoconfmt.interop.cats

import _root_.cats.effect.IO
import fs2.io.file.Path

import scala.scalanative.posix.sys.statOps.statOps
import scala.scalanative.posix.sys.{stat as sys}
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Owner, group and every mode bit, setgid included, which fs2's permission API does not carry. */
object TestPosix {
  final case class Stat(owner: Int, group: Int, mode: Int) derives CanEqual

  def stat(path: Path): IO[Stat] = IO.blocking {
    Zone.acquire { implicit zone =>
      val buffer = stackalloc[sys.stat]()
      if sys.stat(toCString(path.toString), buffer) == 0 then
        Stat(buffer.st_uid.toInt, buffer.st_gid.toInt, buffer.st_mode.toInt & 0xfff)
      else throw new IllegalStateException(s"cannot stat $path")
    }
  }

  def chmod(path: Path, mode: Int): IO[Unit] = IO.blocking {
    Zone.acquire { implicit zone =>
      if sys.chmod(toCString(path.toString), (mode & 0xfff).toUInt) != 0 then
        throw new IllegalStateException(s"cannot chmod $path")
    }
  }

  def chgrp(path: Path, group: Int): IO[Unit] = IO.blocking {
    Zone.acquire { implicit zone =>
      if unistd.chown(toCString(path.toString), (-1).toUInt, group.toUInt) != 0 then
        throw new IllegalStateException(s"cannot chgrp $path")
    }
  }

  /** The file's inode, which a rename changes and a write in place does not. */
  def ino(path: Path): IO[Long] = IO.blocking {
    Zone.acquire { implicit zone =>
      val buffer = stackalloc[sys.stat]()
      if sys.stat(toCString(path.toString), buffer) == 0 then buffer.st_ino.toLong
      else throw new IllegalStateException(s"cannot stat $path")
    }
  }

  /** How many directory entries point at the file's inode. */
  def nlink(path: Path): IO[Long] = IO.blocking {
    Zone.acquire { implicit zone =>
      val buffer = stackalloc[sys.stat]()
      if sys.stat(toCString(path.toString), buffer) == 0 then buffer.st_nlink.toLong
      else throw new IllegalStateException(s"cannot stat $path")
    }
  }

  /** A second directory entry for the same inode. */
  def link(link: Path, existing: Path): IO[Unit] = IO.blocking {
    Zone.acquire { implicit zone =>
      if unistd.link(toCString(existing.toString), toCString(link.toString)) != 0 then
        throw new IllegalStateException(s"cannot link $existing to $link")
    }
  }

  /** A group this process may give a file other than the one `path` is in, where it has one. */
  def otherGroup(path: Path): IO[Option[Int]] =
    stat(path).map { current =>
      Zone.acquire { implicit zone =>
        val size   = 64
        val buffer = stackalloc[sys.gid_t](size)
        val count  = unistd.getgroups(size, buffer)
        (0 until math.max(count, 0)).toList.map(index => buffer(index).toInt).find(_ != current.group)
      }
    }
}
