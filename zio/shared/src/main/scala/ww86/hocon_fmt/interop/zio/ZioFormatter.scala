package ww86.hocon_fmt.interop.zio

import _root_.zio.{IO, UIO, ZIO}
import ww86.hocon_fmt.{HoconFormatter, Refusal, Verdict}

/** The pure formatter suspended in the caller's ZIO runtime. */
object ZioFormatter {
  def format(text: String): IO[Refusal, String] =
    ZIO.suspendSucceed(ZIO.fromEither(HoconFormatter.format(text)))

  def verdict(text: String): UIO[Verdict] = ZIO.succeed(Verdict.of(text))
}
