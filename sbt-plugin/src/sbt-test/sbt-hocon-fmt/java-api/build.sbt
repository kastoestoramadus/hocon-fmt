import java.nio.charset.StandardCharsets.UTF_8
import eu.ww86.hoconfmt.sbt.{IsolatedFormatter, Verdict}

TaskKey[Unit]("assertJavaApiWorker") := {
  val classpath = TaskKey[Seq[File]]("hoconFormatterClasspath").value
  assert(classpath.exists(_.getName.stripSuffix(".jar") == "hocon-fmt-java-api"),
    s"worker must resolve java-api: ${classpath.mkString(", ")}")
  assert(classpath.exists(_.getName.stripSuffix(".jar") == "hocon-fmt-core_3"),
    s"worker must resolve the transitive core: ${classpath.mkString(", ")}")
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
