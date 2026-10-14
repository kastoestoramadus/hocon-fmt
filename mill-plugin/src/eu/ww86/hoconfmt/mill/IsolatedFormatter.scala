package eu.ww86.hoconfmt.mill

import java.lang.reflect.{InvocationTargetException, Method}
import java.net.{URL, URLClassLoader}
import java.nio.file.Path

import scala.jdk.CollectionConverters.*

private[mill] enum Verdict {
  case AlreadyFormatted
  case NeedsFormatting
  case Refused(reason: String)
}

final private[mill] case class Inspection(
    verdict: Verdict,
    warnings: Seq[String],
    failsOnDuplicates: Boolean,
    failsOnRefusal: Boolean
)

/** Only JDK values cross the loader; Mill's compiler never reads the formatter's Scala TASTy. */
final private[mill] class IsolatedFormatter private (inspectFile: Method) {

  def inspect(file: Path, overrides: Map[String, String], write: Boolean): Inspection = {
    val result = try {
      inspectFile.invoke(null, file, overrides.asJava, Boolean.box(write))
    } catch {
      // Refusals are values; an I/O or configuration failure must still fail the task.
      case error: InvocationTargetException => throw error.getCause
    }
    def read(value: AnyRef, key: String): AnyRef = value.getClass.getMethod(key).invoke(value)
    val verdict                                  = read(result, "verdict")
    val mirrored                                 = verdict.getClass.getSimpleName match {
      case "AlreadyFormatted" => Verdict.AlreadyFormatted
      case "NeedsFormatting"  => Verdict.NeedsFormatting
      case "Refused"          => Verdict.Refused(read(verdict, "reason").asInstanceOf[String])
      case unknown            => throw new IllegalStateException(s"unknown Java API verdict: $unknown")
    }
    val report   = read(result, "report")
    val warnings = read(report, "findings")
      .asInstanceOf[java.util.List[AnyRef]]
      .asScala
      .map(finding => read(finding, "warning").asInstanceOf[String])
      .toVector
    val failure = read(report, "failure").asInstanceOf[java.util.Optional[String]]
    Inspection(
      mirrored,
      warnings ++ (if (failure.isPresent) { Seq(s"duplicate report could not run: ${failure.get()}") }
                   else { Nil }),
      read(result, "failsOnDuplicates").asInstanceOf[java.lang.Boolean].booleanValue(),
      read(result, "failsOnRefusal").asInstanceOf[java.lang.Boolean].booleanValue()
    )
  }
}

private[mill] object IsolatedFormatter {

  def using[A](classpath: Seq[URL])(use: IsolatedFormatter => A): A = {
    // The platform parent shares the JDK without letting Mill's Scala library shadow core's.
    val loader = new URLClassLoader(classpath.toArray, ClassLoader.getPlatformClassLoader)
    try {
      val api = loader.loadClass("eu.ww86.hoconfmt.java.HoconFmt")
      use(
        new IsolatedFormatter(
          api.getMethod("inspectFile", classOf[Path], classOf[java.util.Map[?, ?]], java.lang.Boolean.TYPE)
        )
      )
    } finally { loader.close() }
  }
}
