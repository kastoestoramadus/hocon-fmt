package ww86.hocon_fmt

import org.ekrich.config.{Config, ConfigFactory}

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Which token sits between a key and its value in the output. */
enum Separator derives CanEqual {
  case Equals
  case Colon
}

/** The style choices the renderer is asked for, named the same way by the library API, the CLI
  * flags and the `.hocon-fmt.conf` file.
  *
  * Anything not listed here is sconfig's renderer deciding, not an option: see the pins in
  * `OptionsSpec`.
  */
final case class FormatOptions(
    separator: Separator = Separator.Equals,
    doubleIndent: Boolean = false,
    simplifyNestedObjects: Boolean = true
) derives CanEqual

object FormatOptions {

  /** The style a file gets when neither a config file nor the command line says otherwise. */
  val default: FormatOptions = FormatOptions()

  /** The file a repository keeps its style in, looked up from the formatted file upwards. */
  val ConfigFileName = ".hocon-fmt.conf"

  private val KnownKeys = Set("separator", "double-indent", "simplify-nested-objects")

  /** Reads the options from a config file's text. Unknown keys and mistyped values are an error
    * naming the file and the key — never silently ignored.
    */
  def parse(text: String, file: String): Either[String, FormatOptions] =
    Try(ConfigFactory.parseString(text)).toEither.left
      .map(e => s"$file: ${Option(e.getMessage).getOrElse(e.toString)}")
      .flatMap(fromConfig(_, file))

  /** Reads the options from an already parsed config, under the file's name for errors. */
  def fromConfig(config: Config, file: String): Either[String, FormatOptions] = {
    val unknown = config.root.keySet().asScala.filterNot(KnownKeys).toList.sorted
    unknown.headOption match {
      case Some(key) => Left(s"$file: unknown option: $key")
      case None      =>
        for {
          separator             <- separatorOf(config, file)
          doubleIndent          <- booleanOf(config, file, "double-indent", default.doubleIndent)
          simplifyNestedObjects <- booleanOf(config, file, "simplify-nested-objects", default.simplifyNestedObjects)
        } yield FormatOptions(separator, doubleIndent, simplifyNestedObjects)
    }
  }

  // `hasPath` is false for an explicit null, which would then read as "not set"; a null is a
  // mistake to report, not a way to say "default".
  private def isSet(config: Config, key: String): Boolean = config.root.containsKey(key)

  private def separatorOf(config: Config, file: String): Either[String, Separator] =
    if (!isSet(config, "separator")) { Right(default.separator) }
    else {
      Try(config.getString("separator")).toEither match {
        case Right("=")   => Right(Separator.Equals)
        case Right(":")   => Right(Separator.Colon)
        case Right(other) => Left(s"""$file: separator: expected "=" or ":", got: $other""")
        case Left(_)      => Left(s"""$file: separator: expected "=" or ":"""")
      }
    }

  private def booleanOf(config: Config, file: String, key: String, fallback: Boolean): Either[String, Boolean] =
    if (!isSet(config, key)) { Right(fallback) }
    else {
      Try(config.getBoolean(key)).toEither.left
        .map(_ => s"$file: $key: expected true or false")
    }
}
