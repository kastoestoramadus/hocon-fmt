import ww86.hocon_fmt.{HoconFormatter, Verdict}

object CoreExample {
  def main(args: Array[String]): Unit = {
    val source = "app.port=8080"
    println(HoconFormatter.format(source))
    Verdict.of(source) match {
      case Verdict.NeedsFormatting(text) => println(text)
      case Verdict.AlreadyFormatted => println(source)
      case Verdict.Refused(reason) => println(reason.reason)
    }
  }
}
