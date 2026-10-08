package ww86.hocon_fmt.interop.zio

import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import _root_.zio.{Runtime, Unsafe}
import org.scalacheck.Prop.forAll
import ww86.hocon_fmt.Verdict

class FormatterPropertiesSpec extends munit.ScalaCheckSuite {
  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(1000)

  property("arbitrary file bytes are either left intact or replaced with the complete core output") {
    forAll { (content: List[Byte]) =>
      val original = content.toArray
      val path     = Files.createTempFile("hocon-zio-property", ".conf")
      val _        = Files.write(path, original)
      try {
        val result = Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(ZioFiles.format(path).either).getOrThrowFiberFailure()
        }
        // The file's own name is what the decision is made under.
        val expected = Verdict.of(original, path.toString)
        expected match {
          case Verdict.Refused(reason) =>
            assertEquals(result, Left(FileError.Refused(reason)))
            assertEquals(Files.readAllBytes(path).toSeq, original.toSeq)
          case Verdict.AlreadyFormatted =>
            assertEquals(result, Right(expected))
            assertEquals(Files.readAllBytes(path).toSeq, original.toSeq)
          case Verdict.NeedsFormatting(text) =>
            assertEquals(result, Right(expected))
            assertEquals(Files.readAllBytes(path).toSeq, text.getBytes(UTF_8).toSeq)
        }
      } finally {
        val _ = Files.deleteIfExists(path)
      }
    }
  }
}
