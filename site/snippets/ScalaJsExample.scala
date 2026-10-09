import eu.ww86.hoconfmt.{HoconFormatter, Verdict}

object ScalaJsExample {
  def main(args: Array[String]): Unit = {
    println(HoconFormatter.format("app.port=8080"))
    println(Verdict.of("app.port=8080"))
  }
}
