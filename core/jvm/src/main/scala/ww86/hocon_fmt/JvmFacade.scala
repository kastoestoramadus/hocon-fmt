package ww86.hocon_fmt

import java.util.Optional

/** [[Verdict]] for JVM hosts that cannot use Scala types: the Gradle and Maven plugins, written in
  * Java, and the sbt 1.x plugin, which runs on Scala 2.12 and loads this Scala 3 build in an
  * isolated class loader. Only JDK types cross that boundary, so a refusal travels as a checked
  * exception carrying its reason — the idiom a Java caller expects.
  */
object JvmFacade {

  /** The text the file should contain, or empty when it already does. The name is how the caller
    * knows the file: a refusal reports it as the place a parse failed, and a name promising
    * another format is refused outright.
    */
  @throws[FormatRefusedException]("when the file must be left alone; the message says why")
  def reformat(content: Array[Byte], name: String): Optional[String] =
    verdict(Verdict.of(content, name))

  /** As [[reformat(Array[Byte], String)]] for content the caller has no name for. */
  @throws[FormatRefusedException]("when the file must be left alone; the message says why")
  def reformat(content: Array[Byte]): Optional[String] =
    verdict(Verdict.of(content))

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def verdict(decided: Verdict): Optional[String] =
    decided match {
      case Verdict.AlreadyFormatted           => Optional.empty
      case Verdict.NeedsFormatting(formatted) => Optional.of(formatted)
      case Verdict.Refused(refusal)           => throw FormatRefusedException(refusal)
    }
}
