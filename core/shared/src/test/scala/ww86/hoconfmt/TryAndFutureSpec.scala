package ww86.hoconfmt

import java.nio.charset.StandardCharsets.UTF_8

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** The recipes [usage](../../../../../docs/usage.md) gives callers whose channel carries values:
  * the core never throws, so a refusal reaches `Try` or `Future` only when the caller lifts it
  * into [[FormatRefusedException]].
  */
class TryAndFutureSpec extends munit.FunSuite {

  val formatted = "a = 1\n"
  val invalid   = Array(0xff.toByte)

  test("a Try caller turns a verdict into the text or a refusal exception") {
    val source = "a : 1".getBytes(UTF_8)

    val formatted: Try[String] = Try(Verdict.of(source)).flatMap {
      case Verdict.NeedsFormatting(text) => Success(text)
      case Verdict.AlreadyFormatted      => Success(String(source, UTF_8))
      case Verdict.Refused(refusal)      => Failure(FormatRefusedException(refusal))
    }
    assertEquals(formatted, Success(this.formatted))

    val refused: Try[String] = Try(Verdict.of(invalid)).flatMap {
      case Verdict.NeedsFormatting(text) => Success(text)
      case Verdict.AlreadyFormatted      => Success(String(invalid, UTF_8))
      case Verdict.Refused(refusal)      => Failure(FormatRefusedException(refusal))
    }
    refused match {
      case Failure(error: FormatRefusedException) => assertEquals(error.refusal, Refusal.NotUtf8)
      case other                                  => fail(s"expected a refusal, got $other")
    }
  }

  test("a Future caller does the same in its error channel") {
    given ExecutionContext = munitExecutionContext

    val source = "a : 1".getBytes(UTF_8)

    val formatted: Future[String] = Future(Verdict.of(source)).flatMap {
      case Verdict.NeedsFormatting(text) => Future.successful(text)
      case Verdict.AlreadyFormatted      => Future.successful(String(source, UTF_8))
      case Verdict.Refused(refusal)      => Future.failed(FormatRefusedException(refusal))
    }
    val refused: Future[String] = Future(Verdict.of(invalid)).flatMap {
      case Verdict.NeedsFormatting(text) => Future.successful(text)
      case Verdict.AlreadyFormatted      => Future.successful(String(invalid, UTF_8))
      case Verdict.Refused(refusal)      => Future.failed(FormatRefusedException(refusal))
    }

    for {
      text   <- formatted
      _      <- Future.successful(assertEquals(text, this.formatted))
      failed <- refused.failed
    } yield failed match {
      case error: FormatRefusedException => assertEquals(error.refusal, Refusal.NotUtf8)
      case other                         => fail(s"expected a refusal, got $other")
    }
  }
}
