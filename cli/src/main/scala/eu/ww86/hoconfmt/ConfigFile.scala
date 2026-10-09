package eu.ww86.hoconfmt

import _root_.cats.effect.Async
import _root_.cats.syntax.all.*
import fs2.io.file.{Files, Path}

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, StandardCharsets}

/** Finding and reading a repository's `.hocon-fmt.conf`. Filesystem probes and reads stay here; [[ConfigLookup]] decides the walk and
  * [[FormatOptions.parse]] parses values, shared with the build-tool integrations.
  */
object ConfigFile {

  /** The first `.hocon-fmt.conf` in the formatted file's directory or its parents, stopping at
    * the first one found or at a directory holding `.git` — a style from outside the checkout
    * must not reach in. The walk ends at the filesystem root, whose parent is none.
    */
  def discover[F[_]: Async: Files](from: Path): F[Option[Path]] = {
    val config                           = FormatOptions.ConfigFileName
    def walk(dir: Path): F[Option[Path]] =
      (Files[F].exists(dir / config), Files[F].exists(dir / ".git")).mapN(ConfigLookup.decide).flatMap {
        case ConfigLookup.Decision.Found  => (dir / config).some.pure[F]
        case ConfigLookup.Decision.Stop   => none[Path].pure[F]
        case ConfigLookup.Decision.Parent => dir.parent.fold(none[Path].pure[F])(walk)
      }
    from.parent.fold(walk(Path(".")))(walk)
  }

  /** The options the file's own config file names, with the command line's overrides on top. */
  def optionsFor[F[_]: Async: Files](file: Path, overrides: StyleOverrides): F[Either[String, FormatOptions]] =
    discover(file).flatMap {
      case Some(found) => read(found).map(_.map(overrides.applyTo))
      case None        => overrides.applyTo(FormatOptions.default).asRight[String].pure[F]
    }

  /** The options one config file names, decoded strictly as UTF-8 like any formatted file. A
    * file that cannot be read or parsed is an error naming it — never silently ignored.
    */
  def read[F[_]: Async: Files](path: Path): F[Either[String, FormatOptions]] =
    Files[F]
      .readAll(path)
      .compile
      .to(Array)
      .map { bytes =>
        utf8(bytes).left.map(reason => s"$path: $reason").flatMap(FormatOptions.parse(_, path.toString))
      }
      .handleError(e => Left(s"cannot read $path: ${Option(e.getMessage).getOrElse(e.toString)}"))

  private def utf8(bytes: Array[Byte]): Either[String, String] =
    try Right(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString)
    catch { case _: CharacterCodingException => Left("not valid UTF-8") }
}
