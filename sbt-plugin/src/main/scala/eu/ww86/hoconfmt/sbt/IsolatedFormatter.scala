package eu.ww86.hoconfmt.sbt

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

final case class Inspection(
    verdict: Verdict,
    warnings: Seq[String],
    failsOnDuplicates: Boolean,
    failsOnRefusal: Boolean
)

/** The Scala 3 formatter, loaded apart from the host's own Scala library.
  *
  * Only JDK types are shared: the Java API takes the original bytes and a name, and its
  * verdict records are read reflectively. Decoding here would hide invalid UTF-8 from the core.
  *
  * The sources cross-build for the sbt 1 (Scala 2.12) and sbt 2 (Scala 3) hosts, so nothing
  * here may use a construct the two spell differently: no `x: _*` splices, no `_` type
  * wildcards, no `scala.collection.JavaConverters`.
  *
  * Every deviation wartremover sees in this file is the reflection boundary itself: the casts
  * name types that exist only inside the isolated loader, `null` is the receiver of a static
  * `Method.invoke`, and an unknown verdict or a formatter failure must fail the task rather
  * than pass as a value.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Null", "org.wartremover.warts.Throw")
)
final class IsolatedFormatter private (check: Method, formatFile: Method, inspectFile: Method) {

  def verdictFor(content: Array[Byte], name: String): Verdict =
    mirror(unwrap(check.invoke(null, content, name)))

  def format(file: Path): Verdict =
    mirror(unwrap(formatFile.invoke(null, file)))

  def inspect(file: Path, overrides: Map[String, String], write: Boolean): Inspection = {
    // Through `unwrap`, so a rejected setting arrives as the Java API's IllegalArgumentException,
    // message intact, not as the reflection wrapper with no message of its own.
    val overridesJava = new java.util.LinkedHashMap[String, String]()
    overrides.foreach { case (key, value) => overridesJava.put(key, value) }
    val result                                   = unwrap(inspectFile.invoke(null, file, overridesJava, Boolean.box(write)))
    def read(value: AnyRef, key: String): AnyRef = value.getClass.getMethod(key).invoke(value)
    val report                                   = read(result, "report")
    val findings                                 = read(report, "findings").asInstanceOf[java.util.List[AnyRef]]
    val warnings                                 =
      (0 until findings.size()).toVector.map(i => read(findings.get(i), "warning").asInstanceOf[String])
    val failure = read(report, "failure").asInstanceOf[java.util.Optional[String]]
    Inspection(
      mirror(read(result, "verdict")),
      warnings ++ (if (failure.isPresent)
                     Seq("duplicate report could not run: " + failure.get())
                   else Nil),
      read(result, "failsOnDuplicates").asInstanceOf[java.lang.Boolean].booleanValue(),
      read(result, "failsOnRefusal").asInstanceOf[java.lang.Boolean].booleanValue()
    )
  }

  private def mirror(verdict: AnyRef): Verdict = {
    def read(accessor: String): AnyRef = verdict.getClass.getMethod(accessor).invoke(verdict)
    verdict.getClass.getSimpleName match {
      case "AlreadyFormatted" => Verdict.AlreadyFormatted
      case "NeedsFormatting"  => Verdict.NeedsFormatting(read("formatted").asInstanceOf[String])
      case "Refused"          => Verdict.Refused(kindName(read("kind")), read("reason").asInstanceOf[String])
      case unknown            => throw new IllegalStateException(s"unknown Java API verdict: $unknown")
    }
  }

  // The kind is a JDK enum across the loader and only its name is read; Enum[Nothing] names the
  // type without a wildcard, which the hosts' Scala versions spell differently.
  private def kindName(kind: AnyRef): String =
    kind.asInstanceOf[java.lang.Enum[Nothing]].name()

  // A refusal is a value, but a failure inside the formatter must fail the task.
  private def unwrap[A](call: => A): A =
    try call
    catch { case e: InvocationTargetException => throw e.getCause }

}

object IsolatedFormatter {

  def using[A](classpath: Seq[File])(use: IsolatedFormatter => A): A = {
    // With the platform loader as parent, the two sides share the JDK and nothing else, so the
    // host's Scala library cannot shadow the formatter's.
    val loader = new URLClassLoader(classpath.map(_.toURI.toURL).toArray, ClassLoader.getPlatformClassLoader)
    try {
      val api = loader.loadClass("eu.ww86.hoconfmt.java.HoconFmt")
      use(
        new IsolatedFormatter(
          // `check` is overloaded between text and bytes; the worker sends the original bytes,
          // so invalid UTF-8 stays a refusal instead of a replacement character. The entry
          // points are matched by their exact parameter types because a wildcard type literal,
          // the other way to write this, is spelled differently on the two hosts.
          method(api, "check", classOf[Array[Byte]], classOf[String]),
          method(api, "formatFile", classOf[Path]),
          method(api, "inspectFile", classOf[Path], classOf[java.util.Map[AnyRef, AnyRef]], classOf[Boolean])
        )
      )
    } finally loader.close()
  }

  // The one public method with this name and exactly these parameter types. An arity or
  // first-parameter match would let a future overload change which method runs; a rename would
  // fail with no name. Both fail here instead, naming the signature the call site wants.
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def method(api: Class[?], name: String, types: Class[?]*): Method = {
    val found = api.getMethods.filter(m => m.getName == name && m.getParameterTypes.toSeq == types)
    if (found.size == 1) found.head
    else
      throw new IllegalStateException(
        s"expected exactly one $name(${types.map(_.getName).mkString(", ")}) on ${api.getName}, " +
          s"found ${found.size}: ${found.map(_.toString).mkString(", ")}"
      )
  }
}
