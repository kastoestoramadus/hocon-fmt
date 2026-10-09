import ww86.hocon_fmt.web.HoconFormatterJs

object ScalaJsExample {
  def main(args: Array[String]): Unit = {
    val result = HoconFormatterJs.format("app.port=8080")
    println(result.verdict)
    println(result.formatted.getOrElse("refused"))
  }
}
