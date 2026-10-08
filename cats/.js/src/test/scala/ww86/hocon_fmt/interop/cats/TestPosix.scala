package ww86.hocon_fmt.interop.cats

import _root_.cats.effect.IO
import fs2.io.file.Path

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Owner, group and every mode bit, setgid included, which fs2's permission API does not carry. */
object TestPosix {
  final case class Stat(owner: Int, group: Int, mode: Int) derives CanEqual

  @js.native
  @JSImport("fs", JSImport.Namespace)
  object NodeFs extends js.Object {
    def statSync(path: String): js.Dynamic                = js.native
    def chmodSync(path: String, mode: Int): Unit          = js.native
    def chownSync(path: String, uid: Int, gid: Int): Unit = js.native
  }

  def stat(path: Path): IO[Stat] = IO {
    val attributes = NodeFs.statSync(path.toString)
    Stat(number(attributes, "uid"), number(attributes, "gid"), number(attributes, "mode") & 0xfff)
  }

  def chmod(path: Path, mode: Int): IO[Unit] = IO(NodeFs.chmodSync(path.toString, mode & 0xfff))

  def chgrp(path: Path, group: Int): IO[Unit] =
    stat(path).flatMap(current => IO(NodeFs.chownSync(path.toString, current.owner, group)))

  /** A group this process may give a file other than the one `path` is in, where it has one. */
  def otherGroup(path: Path): IO[Option[Int]] =
    stat(path).map { current =>
      val getgroups = js.Dynamic.global.process.selectDynamic("getgroups")
      if js.typeOf(getgroups) == "function" then
        getgroups
          .asInstanceOf[js.Function0[js.Array[Double]]]
          .apply()
          .toList
          .map(_.toInt)
          .find(_ != current.group)
      else None
    }

  def number(attributes: js.Dynamic, name: String): Int = {
    val value = attributes.selectDynamic(name)
    if js.typeOf(value) == "number" then value.asInstanceOf[Double].toInt
    else throw new IllegalStateException(s"no $name in the node stat result")
  }
}
