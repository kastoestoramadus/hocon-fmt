import sbt._
import org.ekrich.config.ConfigFactory
import scala.collection.JavaConverters._

/** Read HOCON at build time so every runtime receives the same immutable fixtures. */
object ExampleGenerator {
  def generate(root: File, managed: File): Seq[File] = {
    def quoted(value: String): String = {
      val escaped = value.flatMap {
        case '"'          => "\\\""
        case '\\'         => "\\\\"
        case '\n'         => "\\n"
        case '\r'         => "\\r"
        case '\t'         => "\\t"
        case c if c < ' ' => "\\u%04x".format(c.toInt)
        case c            => c.toString
      }
      "\"" + escaped + "\""
    }
    def list(values: Seq[String]): String                       = values.map(quoted).mkString("List(", ", ", ")")
    val kinds = Set("not-utf8", "not-hocon", "broken-output", "lost-comment", "lost-include", "moved-include", "unstable-output")
    def verdict(value: String, target: Boolean = false): String = {
      require(
        value == "formatted" || (!target && value == "already-formatted") ||
          (value.startsWith("refused:") && kinds(value.stripPrefix("refused:"))),
        s"Unknown verdict: $value"
      )
      value
    }
    val entries = Seq("showcase", "catalogue").flatMap { section =>
      val directory = root / section
      Option(directory.listFiles).toSeq.flatten.filter(_.isDirectory).sortBy(_.getName).map { dir =>
        val id = section + "/" + dir.getName
        require(section != "showcase" || dir.getName.matches("[0-9]{2}-.+"), s"$id: expected NN-slug")
        val config                   = ConfigFactory.parseFile(dir / "example.conf").resolve()
        def str(key: String): String = {
          val value = config.getString(key)
          require(value.trim.nonEmpty, s"$id: empty $key")
          value
        }
        def optional(key: String): String =
          if (config.hasPath(key)) "Some(" + quoted(str(key)) + ")" else "None"
        val target = verdict(str("target"), target = true)
        val now    = verdict(str("now"))
        if (config.hasPath("pending")) verdict(str("pending"))
        val sourceKind = str("source.kind")
        require(Set("synthetic", "distilled", "verbatim")(sourceKind), s"$id: unknown source kind")
        val pattern = str("source.pattern")
        if (sourceKind == "verbatim") { str("source.url"); str("source.licence") }
        val options = config.getStringList("options").asScala.toList
        require(options == List("default"), s"$id: only options: [default] is supported")
        val expected =
          if (section == "showcase" && now.startsWith("refused:")) "Map.empty[String, String]"
          else {
            val expectedFile = dir / "expected" / (if (now.startsWith("refused:")) "refused.txt" else "default.conf")
            require(expectedFile.isFile, s"$id: missing $expectedFile")
            s"""Map("default" -> ${quoted(IO.read(expectedFile))})"""
          }
        val input = dir / "input.conf"
        require(input.isFile, s"$id: missing input.conf")
        val findings = if (config.hasPath("findings")) config.getStringList("findings").asScala.toList else Nil
        s"Example(${quoted(id)}, ${quoted(str("title"))}, ${quoted(str("story"))}, ${quoted(str("shows"))}, " +
          s"${quoted(IO.read(input))}, ${quoted(target)}, ${quoted(now)}, ${optional("pending")}, " +
          s"${optional("reason-if-different")}, ${list(findings)}, " +
          s"ExampleSource(${quoted(sourceKind)}, ${quoted(pattern)}, ${optional("source.url")}, ${optional("source.licence")}), " +
          s"${list(options)}, $expected)"
      }
    }
    val output = managed / "ww86" / "hocon_fmt" / "ExampleData.scala"
    val text   = "package ww86.hocon_fmt\n\n" +
      """final case class ExampleSource(kind: String, pattern: String, url: Option[String], licence: Option[String])

/** Human-authored expectations, embedded at build time without runtime file access. */
final case class Example(
    id: String,
    title: String,
    story: String,
    shows: String,
    input: String,
    target: String,
    now: String,
    pending: Option[String],
    reasonIfDifferent: Option[String],
    findings: List[String],
    source: ExampleSource,
    options: List[String],
    expected: Map[String, String]
)
""" + "\nobject ExampleData {\n" +
      entries.mkString("  val all: List[Example] = List(\n    ", ",\n    ", "\n  )\n") +
      "  val kinds: Set[String] = Set(" + kinds.toSeq.sorted.map(quoted).mkString(", ") + ")\n" +
      "  val showcase: List[Example] = all.filter(_.id.startsWith(\"showcase/\"))\n}\n"
    if (!output.exists || IO.read(output) != text) IO.write(output, text)
    Seq(output)
  }
}
