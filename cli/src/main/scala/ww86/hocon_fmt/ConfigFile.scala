package ww86.hocon_fmt

import _root_.cats.effect.Async
import _root_.cats.syntax.all.*
import fs2.io.file.{Files, Path}

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, StandardCharsets}

/** The options the command line gives a file. A field that is set wins over the config file; one
  * left unset leaves the file's own `.hocon-fmt.conf`, or the default, in charge.
  */
final case class StyleOverrides(
    separator: Option[Separator] = None,
    doubleIndent: Option[Boolean] = None,
    simplifyNestedObjects: Option[Boolean] = None,
    failOnDuplicates: Option[Boolean] = None
) derives CanEqual {

  def applyTo(base: FormatOptions): FormatOptions =
    FormatOptions(
      separator = separator.getOrElse(base.separator),
      doubleIndent = doubleIndent.getOrElse(base.doubleIndent),
      simplifyNestedObjects = simplifyNestedObjects.getOrElse(base.simplifyNestedObjects),
      failOnDuplicates = failOnDuplicates.getOrElse(base.failOnDuplicates)
    )
}

object StyleOverrides {
  val none: StyleOverrides = StyleOverrides()
}

/** Finding and reading a repository's `.hocon-fmt.conf`. The lookup and the file reading are
  * effects, so they live here in the cli; parsing the option values is [[FormatOptions.parse]],
  * shared with the core, so the build-tool plugins can reuse it when they grow config-file
  * support after their migration to the java API.
  */
object ConfigFile {

  /** The first `.hocon-fmt.conf` in the formatted file's directory or its parents, stopping at
    * the first one found or at a directory holding `.git` — a style from outside the checkout
    * must not reach in. The walk ends at the filesystem root, whose parent is none.
    */
  def discover[F[_]: Async: Files](from: Path): F[Option[Path]] = {
    val config                           = FormatOptions.ConfigFileName
    def walk(dir: Path): F[Option[Path]] =
      Files[F].exists(dir / config).flatMap {
        case true  => (dir / config).some.pure[F]
        case false =>
          Files[F].exists(dir / ".git").flatMap {
            case true  => none[Path].pure[F]
            case false => dir.parent.fold(none[Path].pure[F])(walk)
          }
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
