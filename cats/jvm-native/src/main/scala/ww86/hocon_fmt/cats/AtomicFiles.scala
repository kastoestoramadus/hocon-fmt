package ww86.hocon_fmt.cats

import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}

private[cats] object AtomicFiles {
  def copyAttributes[F[_]](files: Files[F], source: Path, target: Path): F[Unit] =
    files.copy(source, target, CopyFlags(CopyFlag.ReplaceExisting, CopyFlag.CopyAttributes))

  def move[F[_]](files: Files[F], source: Path, target: Path): F[Unit] =
    files.move(source, target, CopyFlags(CopyFlag.AtomicMove, CopyFlag.ReplaceExisting))
}
