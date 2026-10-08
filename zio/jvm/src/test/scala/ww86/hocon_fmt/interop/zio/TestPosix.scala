package ww86.hocon_fmt.interop.zio

import java.nio.file.Files as NioFiles
import java.nio.file.Path

/** Owner, group and every mode bit, setgid included, which the nine-bit permissions API misses. */
object TestPosix {
  final case class Stat(owner: Int, group: Int, mode: Int) derives CanEqual

  def stat(path: Path): Stat = {
    val attributes = NioFiles.readAttributes(path, "unix:uid,gid,mode")
    (attributes.get("uid"), attributes.get("gid"), attributes.get("mode")) match {
      case (owner: Integer, group: Integer, mode: Integer) =>
        Stat(owner.intValue, group.intValue, mode.intValue & 0xfff)
      case other => throw new IllegalStateException(s"no unix attributes: $other")
    }
  }

  def chmod(path: Path, mode: Int): Unit = {
    val _ = NioFiles.setAttribute(path, "unix:mode", Int.box(mode & 0xfff))
  }

  def chgrp(path: Path, group: Int): Unit = {
    val _ = NioFiles.setAttribute(path, "unix:gid", Int.box(group))
  }

  /** The file's inode, which a rename changes and a write in place does not. */
  def ino(path: Path): Long = {
    val attributes = NioFiles.readAttributes(path, "unix:ino")
    attributes.get("ino") match {
      case value: java.lang.Long => value.longValue
      case other                 => throw new IllegalStateException(s"no unix attributes: $other")
    }
  }

  /** How many directory entries point at the file's inode. */
  def nlink(path: Path): Long = {
    val attributes = NioFiles.readAttributes(path, "unix:nlink")
    attributes.get("nlink") match {
      case value: Number => value.longValue
      case other         => throw new IllegalStateException(s"no unix attributes: $other")
    }
  }

  /** A group this process may give a file other than the one `path` is in, where it has one. */
  def otherGroup(path: Path): Option[Int] = {
    val current = stat(path)
    val unix    = new com.sun.security.auth.module.UnixSystem()
    unix.getGroups.toList.map(_.intValue).find(_ != current.group)
  }
}
