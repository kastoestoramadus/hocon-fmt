package ww86.hocon_fmt.sbt

import java.io.File
import java.lang.reflect.{InvocationTargetException, Method}
import java.net.URLClassLoader

/** What the formatter makes of one file, as the plugin sees it from across the class loaders. */
sealed trait Verdict

object Verdict {
  case object AlreadyFormatted                           extends Verdict
  final case class NeedsFormatting(formatted: String)    extends Verdict
  final case class Refused(kind: String, reason: String) extends Verdict
}

/** The Scala 3 formatter, loaded apart from sbt's own Scala 2.12 library.
  *
  * Only JDK types are shared: the Java API takes the original bytes and a name, and its
  * verdict records are read reflectively. Decoding here would hide invalid UTF-8 from the core.
  */
final class IsolatedFormatter private (check: Method) {

  def verdictFor(content: Array[Byte], name: String): Verdict =
    try {
      val verdict                        = check.invoke(null, content, name)
      def read(accessor: String): AnyRef = verdict.getClass.getMethod(accessor).invoke(verdict)
      verdict.getClass.getSimpleName match {
        case "AlreadyFormatted" => Verdict.AlreadyFormatted
        case "NeedsFormatting"  => Verdict.NeedsFormatting(read("formatted").asInstanceOf[String])
        case "Refused"          =>
          Verdict.Refused(read("kind").asInstanceOf[Enum[_]].name(), read("reason").asInstanceOf[String])
        case unknown =>
          throw new IllegalStateException(s"the Java API grew a verdict the plugin does not know: $unknown")
      }
    } catch {
      // Refusals are values; a failure inside the formatter must fail the task.
      case e: InvocationTargetException => throw e.getCause
    }
}

object IsolatedFormatter {

  def using[A](classpath: Seq[File])(use: IsolatedFormatter => A): A = {
    // With the platform loader as parent, the two sides share the JDK and nothing else, so sbt's
    // Scala 2.12 library cannot shadow the formatter's Scala 3 one.
    val loader = new URLClassLoader(classpath.map(_.toURI.toURL).toArray, ClassLoader.getPlatformClassLoader)
    try {
      val check = loader
        .loadClass("ww86.hocon_fmt.java.HoconFmt")
        .getMethod("check", classOf[Array[Byte]], classOf[String])
      use(new IsolatedFormatter(check))
    } finally loader.close()
  }
}
