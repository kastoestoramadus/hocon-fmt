package ww86.hocon_fmt.cats

import _root_.cats.effect.Async
import _root_.cats.syntax.all.*
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}

private[cats] object AtomicFiles {
  def copyAttributes[F[_]: Async](files: Files[F], source: Path, target: Path): F[Unit] =
    files.getPosixPermissions(source).flatMap(files.setPosixPermissions(target, _))

  // fs2 on Node uses rename, which atomically replaces a sibling on the same filesystem.
  def move[F[_]](files: Files[F], source: Path, target: Path): F[Unit] =
    files.move(source, target, CopyFlags(CopyFlag.ReplaceExisting))
}
