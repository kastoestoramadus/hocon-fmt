import zio.{ZIO, ZIOAppDefault}
import ww86.hoconfmt.interop.zio.ZioFormatter

object ZioExample extends ZIOAppDefault {
  val run = ZioFormatter.verdict("app.port=8080").flatMap(value => ZIO.succeed(println(value)))
}
