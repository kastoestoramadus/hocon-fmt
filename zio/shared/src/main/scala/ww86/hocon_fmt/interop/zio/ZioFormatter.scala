package ww86.hocon_fmt.interop.zio

import _root_.zio.{IO, UIO, ZIO}
import ww86.hocon_fmt.{HoconFormatter, Refusal, Verdict}

/** The pure formatter suspended in the caller's ZIO runtime. */
object ZioFormatter {
  def format(text: String): IO[Refusal, String] =
    ZIO.suspendSucceed(ZIO.fromEither(HoconFormatter.format(text)))

  /** As [[format(String)]], with `origin` naming where the text came from, so a refusal reports
    * `conf/application.conf: 8: ...` rather than `String: 8: ...`.
    */
  def format(text: String, origin: String): IO[Refusal, String] =
    ZIO.suspendSucceed(ZIO.fromEither(HoconFormatter.format(text, origin)))

  def verdict(text: String): UIO[Verdict] = ZIO.succeed(Verdict.of(text))

  /** As [[verdict(String)]], with `name` the name the caller knows the text by: a name promising
    * another format rules the text out, and a parse failure reports it as the origin.
    */
  def verdict(text: String, name: String): UIO[Verdict] = ZIO.succeed(Verdict.of(text, name))
}
