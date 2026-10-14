import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import eu.ww86.hoconfmt.sbt.{IsolatedFormatter, Verdict}

TaskKey[Unit]("assertWorkerIsolation") := {
  val classpath = TaskKey[Seq[File]]("hoconFormatterClasspath").value
  def jarNamed(prefix: String) = classpath.exists(_.getName.stripSuffix(".jar").startsWith(prefix))
  assert(jarNamed("hocon-fmt-java-api"),
    s"worker must resolve java-api: ${classpath.mkString(", ")}")
  assert(jarNamed("hocon-fmt-core_3"),
    s"worker must resolve the transitive core: ${classpath.mkString(", ")}")
  // The formatter's Scala crosses as its own 3.9.0 jars; the host's 3.8.4 stays out.
  assert(classpath.exists(_.getName.startsWith("scala3-library_3-3.9.0")),
    s"worker must carry its own Scala 3.9.0 library: ${classpath.mkString(", ")}")
  assert(classpath.exists(_.getName.startsWith("scala-library-3.9.0")),
    s"worker must carry its own 3.9.0 scala-library: ${classpath.mkString(", ")}")
  val hostScala = classpath.filter(_.getName.contains("3.8.4"))
  assert(hostScala.isEmpty,
    s"the host's Scala must stay off the worker classpath: ${hostScala.mkString(", ")}")

  // The isolated loader loads its own scala.Predef, distinct from the one sbt runs on: sbt's
  // Scala 3.8 and the formatter's 3.9 never meet in one class loader, so neither reads the
  // other's TASTy.
  val isolated = new URLClassLoader(classpath.map(_.toURI.toURL).toArray, ClassLoader.getPlatformClassLoader)
  try {
    val workerPredef = isolated.loadClass("scala.Predef")
    val hostPredef   = getClass.getClassLoader.loadClass("scala.Predef")
    assert(workerPredef ne hostPredef,
      s"the worker loaded the host's scala.Predef from ${workerPredef.getProtectionDomain.getCodeSource}")
    assert(workerPredef.getProtectionDomain.getCodeSource.getLocation.getPath.contains("3.9.0"),
      s"the worker's scala.Predef does not come from 3.9.0: ${workerPredef.getProtectionDomain.getCodeSource}")
  } finally isolated.close()

  IsolatedFormatter.using(classpath) { formatter =>
    assert(formatter.verdictFor("a = 1\n".getBytes(UTF_8), "app.conf") == Verdict.AlreadyFormatted)
    assert(formatter.verdictFor("a   :   1".getBytes(UTF_8), "app.conf") == Verdict.NeedsFormatting("a = 1\n"))
    assert(formatter.verdictFor(Array(0xff.toByte, 10.toByte), "app.conf").toString ==
      "Refused(NotUtf8,not valid UTF-8)")
    val refused = formatter.verdictFor("a : ${".getBytes(UTF_8), "conf/application.conf")
    assert(refused.toString.contains("not valid HOCON: conf/application.conf:"), refused.toString)
    val other = formatter.verdictFor("{\"a\": 1}".getBytes(UTF_8), "application.json")
    assert(other.toString.contains("a JSON file"), other.toString)
  }
}
