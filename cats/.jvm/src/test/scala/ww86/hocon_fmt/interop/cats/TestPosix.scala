package ww86.hocon_fmt.interop.cats

import java.nio.file.Files as NioFiles

import _root_.cats.effect.IO
import fs2.io.file.Path

/** Owner, group and every mode bit, setgid included, which fs2's permission API does not carry. */
object TestPosix {
  final case class Stat(owner: Int, group: Int, mode: Int) derives CanEqual

  def stat(path: Path): IO[Stat] = IO.blocking {
    val attributes = NioFiles.readAttributes(path.toNioPath, "unix:uid,gid,mode")
    (attributes.get("uid"), attributes.get("gid"), attributes.get("mode")) match {
      case (owner: Integer, group: Integer, mode: Integer) =>
        Stat(owner.intValue, group.intValue, mode.intValue & 0xfff)
      case other => throw new IllegalStateException(s"no unix attributes: $other")
    }
  }

  def chmod(path: Path, mode: Int): IO[Unit] =
    IO.blocking(NioFiles.setAttribute(path.toNioPath, "unix:mode", Int.box(mode))).void

  def chgrp(path: Path, group: Int): IO[Unit] =
    IO.blocking(NioFiles.setAttribute(path.toNioPath, "unix:gid", Int.box(group))).void

  /** A group this process may give a file other than the one `path` is in, where it has one. */
  def otherGroup(path: Path): IO[Option[Int]] =
    stat(path).map { current =>
      val unix = new com.sun.security.auth.module.UnixSystem()
      unix.getGroups.toList.map(_.intValue).find(_ != current.group)
    }
}
