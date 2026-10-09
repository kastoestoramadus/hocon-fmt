import cats.effect.{IO, IOApp}
import fs2.io.file.Path
import ww86.hoconfmt.interop.cats.FileFormatter

object CatsExample extends IOApp.Simple {
  val run: IO[Unit] = {
    val formatter = FileFormatter[IO]
    formatter.verdict(Path("application.conf")).flatMap(IO.println)
  }
}
