import sbt._

object UseItGenerator {
  def quoted(text: String): String = {
    "\"" + text.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case c    => c.toString
    } + "\""
  }

  def generate(root: File, out: File): Seq[File] = {
    val usage                          = IO.read(root / "docs" / "usage.md")
    def block(section: String): String = {
      val text  = usage.substring(usage.indexOf("## " + section + "\n"))
      val fence = text.indexOf("```")
      val start = text.indexOf('\n', fence) + 1
      text.substring(start, text.indexOf("```", start)).trim
    }
    val commands = Seq(
      "native"     -> "pipx install hocon-fmt",
      "npm"        -> "npx hocon-fmt",
      "jvm"        -> "sbt \"cliJVM/run <args>\"",
      "pre-commit" -> block("pre-commit"),
      "sbt"        -> block("sbt"),
      "gradle"     -> block("Gradle"),
      "maven"      -> block("Maven"),
      "mill"       -> block("Mill")
    )
    val actions = Seq(
      "native"     -> Seq("hocon-fmt --check src/"),
      "npm"        -> Seq("hocon-fmt --check src/"),
      "jvm"        -> Seq("hocon-fmt --check src/"),
      "pre-commit" -> Seq("hocon-fmt-node", "hocon-fmt-check-node"),
      "sbt"        -> Seq("hoconFormat", "hoconFormatCheck"),
      "gradle"     -> Seq("hoconFormat", "hoconFormatCheck"),
      "maven"      -> Seq("mvn hocon-fmt:format", "mvn hocon-fmt:check"),
      "mill"       -> Seq("./mill __.hoconFormat", "./mill __.hoconFormatCheck")
    )
    actions.flatMap(_._2).foreach(command => require(usage.contains(command), command))
    commands.foreach { case (_, command) =>
      require(usage.contains(command), "Use it command differs from docs/usage.md: " + command)
    }
    val snippets = Seq(
      "core"    -> "CoreExample.scala",
      "cats"    -> "CatsExample.scala",
      "zio"     -> "ZioExample.scala",
      "java"    -> "JavaExample.java",
      "scalajs" -> "ScalaJsExample.scala"
    )
      .map { case (key, name) => key -> IO.read(root / "site" / "snippets" / name) }
    def entries(values: Seq[(String, String)]): String =
      values.map { case (key, value) => quoted(key) + " -> " + quoted(value) }.mkString(",\n")
    val file = out / "ww86" / "hoconfmt" / "site" / "UseItExamples.scala"
    IO.write(
      file,
      "package ww86.hoconfmt.site\nobject UseItExamples {\n" +
        "val commands: Map[String, String] = Map(" + entries(commands) + ")\n" +
        "val actions: Map[String, List[String]] = Map(" +
        actions
          .map { case (key, values) => quoted(key) + " -> List(" + values.map(quoted).mkString(", ") + ")" }
          .mkString(", ") + ")\n" +
        "val snippets: Map[String, String] = Map(" + entries(snippets) + ")\n}\n"
    )
    Seq(file)
  }
}
