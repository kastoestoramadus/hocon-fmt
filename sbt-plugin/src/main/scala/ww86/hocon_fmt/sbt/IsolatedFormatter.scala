package ww86.hocon_fmt.sbt

import java.io.File
import java.lang.reflect.{InvocationTargetException, Method}
import java.net.URLClassLoader
import java.nio.file.Path

/** What the formatter makes of one file, as the plugin sees it from across the class loaders. */
sealed trait Verdict

object Verdict {
  case object AlreadyFormatted                           extends Verdict
  final case class NeedsFormatting(formatted: String)    extends Verdict
  final case class Refused(kind: String, reason: String) extends Verdict
}

final case class Inspection(verdict: Verdict, warnings: Seq[String], failsOnDuplicates: Boolean)

/** The Scala 3 formatter, loaded apart from sbt's own Scala 2.12 library.
  *
  * Only JDK types are shared: the Java API takes the original bytes and a name, and its
  * verdict records are read reflectively. Decoding here would hide invalid UTF-8 from the core.
  */
final class IsolatedFormatter private (check: Method, formatFile: Method, inspectFile: Method) {

  def verdictFor(content: Array[Byte], name: String): Verdict =
    invoke(check, content, name)

  def format(file: Path): Verdict = invoke(formatFile, file)

  def inspect(file: Path, overrides: Map[String, String], write: Boolean): Inspection = {
    import scala.collection.JavaConverters._
    val result                                   = inspectFile.invoke(null, file, overrides.asJava, Boolean.box(write))
    def read(value: AnyRef, key: String): AnyRef = value.getClass.getMethod(key).invoke(value)
    val report                                   = read(result, "report")
    val findings                                 = read(report, "findings")
      .asInstanceOf[java.util.List[AnyRef]]
      .asScala
      .map(finding => read(finding, "warning").asInstanceOf[String])
      .toVector
    val failure = read(report, "failure").asInstanceOf[java.util.Optional[String]]
    Inspection(
      mirror(read(result, "verdict")),
      findings ++ (if (failure.isPresent)
                     Seq("duplicate report could not run: " + failure.get())
                   else Nil),
      read(result, "failsOnDuplicates").asInstanceOf[java.lang.Boolean].booleanValue()
    )
  }

  private def mirror(verdict: AnyRef): Verdict = {
    def read(accessor: String): AnyRef = verdict.getClass.getMethod(accessor).invoke(verdict)
    verdict.getClass.getSimpleName match {
      case "AlreadyFormatted" => Verdict.AlreadyFormatted
      case "NeedsFormatting"  => Verdict.NeedsFormatting(read("formatted").asInstanceOf[String])
      case "Refused"          => Verdict.Refused(read("kind").asInstanceOf[Enum[_]].name(), read("reason").asInstanceOf[String])
      case unknown            => throw new IllegalStateException(s"unknown Java API verdict: $unknown")
    }
  }

  private def invoke(entry: Method, arguments: AnyRef*): Verdict = mirror(call(entry, arguments: _*))

  private def call(entry: Method, arguments: AnyRef*): AnyRef =
    try entry.invoke(null, arguments: _*)
    catch {
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
      val api = loader.loadClass("ww86.hocon_fmt.java.HoconFmt")
      use(
        new IsolatedFormatter(
          api.getMethod("check", classOf[Array[Byte]], classOf[String]),
          api.getMethod("formatFile", classOf[Path]),
          api.getMethod("inspectFile", classOf[Path], classOf[java.util.Map[_, _]], java.lang.Boolean.TYPE)
        )
      )
    } finally loader.close()
  }
}
