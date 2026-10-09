package eu.ww86.hoconfmt

import _root_.cats.effect.Async
import _root_.cats.syntax.all.*
import fs2.io.file.{Files, Path}

import java.nio.charset.StandardCharsets.UTF_8

/** Expanding the arguments of a run: a file stays as it is, a directory is walked for `*.conf`
  * and `*.hocon`, the way prettier and ruff walk. Hidden entries are skipped, a symlinked
  * directory is not entered, and what `.gitignore` excludes is never visited — git cannot
  * re-include below an excluded directory, so the walk stops there too. The ignores are every
  * `.gitignore` from the checkout root (the directory holding `.git`, where the walk up stops,
  * as the config-file lookup does) down to the argument, and in every directory below it; a
  * deeper file's match beats a shallower one's. A file named on the command line is examined even
  * when ignored: the user typed that path, so it is not walked.
  */
object Walk {

  private val extensions = Set("conf", "hocon")

  /** A path the run could not read, with the reason in words to report. */
  final case class Unreadable(path: Path, reason: String)

  /** The files a run examines, and the arguments it could not read. */
  final case class Expansion(files: List[Path], unreadable: List[Unreadable]) {
    def append(other: Expansion): Expansion = Expansion(files ++ other.files, unreadable ++ other.unreadable)
  }

  object Expansion {
    val empty: Expansion = Expansion(Nil, Nil)
  }

  def expand[F[_]: Async: Files](paths: List[Path]): F[Expansion] =
    paths.traverse(root).map(_.foldLeft(Expansion.empty)(_.append(_)))

  private def root[F[_]: Async: Files](path: Path): F[Expansion] =
    Files[F].exists(path).flatMap {
      case false => Expansion(Nil, List(Unreadable(path, "no such file"))).pure[F]
      case true  =>
        Files[F].isDirectory(path).flatMap {
          case true  => ancestors(path.parent).flatMap(walk(path, _))
          case false => Expansion(List(path), Nil).pure[F]
        }
    }

  /** One `.gitignore` and the directory its patterns are relative to. */
  final private case class IgnoreFile(base: Path, ignore: GitIgnore)

  // Shallowest first, so a deeper file gets the last word when the list is matched in reverse.
  private def ancestors[F[_]: Async: Files](from: Option[Path]): F[List[IgnoreFile]] = {
    def up(dir: Path): F[List[IgnoreFile]] =
      ignoreFile(dir).flatMap { here =>
        Files[F].exists(dir / ".git").flatMap {
          case true  => here.toList.pure[F] // the checkout's own ignores, and nothing above it
          case false => dir.parent.fold(here.toList.pure[F])(up).map(here.toList ::: _)
        }
      }
    from.fold(List.empty[IgnoreFile].pure[F])(up).map(_.reverse)
  }

  private def ignoreFile[F[_]: Async: Files](dir: Path): F[Option[IgnoreFile]] =
    Files[F].exists(dir / ".gitignore").flatMap {
      case false => None.pure[F]
      case true  =>
        Files[F].readAll(dir / ".gitignore").compile.to(Array).attempt.map {
          // An unreadable .gitignore does not stop the run: what it might have excluded is
          // examined, which loses at most formatting, never bytes.
          case Right(bytes) => Some(IgnoreFile(dir, GitIgnore.parse(new String(bytes, UTF_8))))
          case Left(_)      => None
        }
    }

  private def walk[F[_]: Async: Files](dir: Path, outer: List[IgnoreFile]): F[Expansion] =
    ignoreFile(dir).flatMap { here =>
      val active = here.fold(outer)(outer :+ _)
      Files[F].list(dir).compile.toList.attempt.flatMap {
        case Left(e)        => Expansion(Nil, List(Unreadable(dir, ReadFailure.message(e)))).pure[F]
        case Right(entries) =>
          val visible = entries
            .filterNot(entry => entry.fileName.toString.startsWith("."))
            .sortBy(_.toString)
          visible.traverse(visit(_, active)).map(_.foldLeft(Expansion.empty)(_.append(_)))
      }
    }

  private def visit[F[_]: Async: Files](path: Path, active: List[IgnoreFile]): F[Expansion] =
    Files[F].isSymbolicLink(path).flatMap {
      // A linked file formats through its target; a linked directory is not entered.
      case true  => Async[F].pure(kept(path, active))
      case false =>
        Files[F].isDirectory(path).flatMap {
          case true =>
            if (excluded(path, active, isDirectory = true)) Expansion.empty.pure[F] else walk(path, active)
          case false => Async[F].pure(kept(path, active))
        }
    }

  // A file the walk visits: examined only when its extension names HOCON and no pattern excludes it.
  private def kept(file: Path, active: List[IgnoreFile]): Expansion = {
    val name  = file.fileName.toString
    val dot   = name.lastIndexOf('.')
    val hocon = dot >= 0 && extensions.contains(name.substring(dot + 1))
    if (hocon && !excluded(file, active, isDirectory = false)) Expansion(List(file), Nil) else Expansion.empty
  }

  // Patterns are relative to their own .gitignore; the walk's paths are built by joining, so the
  // relative form is the text after the base. A deeper file decides, then the last pattern in it.
  private def excluded(path: Path, active: List[IgnoreFile], isDirectory: Boolean): Boolean = {
    def relative(base: Path): Option[String] =
      Option.when(path.toString.startsWith(base.toString + "/"))(path.toString.drop(base.toString.length + 1))
    active.reverse.iterator
      .flatMap(file => relative(file.base).flatMap(rel => file.ignore.decision(rel, isDirectory)))
      .collectFirst { case answer => answer }
      .getOrElse(false)
  }
}
