TaskKey[Unit]("assertUntouched") := {
  assert(IO.readBytes(baseDirectory.value / "src/main/resources/refused.conf")
    .sameElements(IO.readBytes(baseDirectory.value / "original.conf")))
}

// `must-mirror` compares decoded lines, so it cannot see a non-UTF-8 byte replaced by U+FFFD.
InputKey[Unit]("assertSameBytes") := {
  val Seq(actual, expected) = Def.spaceDelimited("<file> <file>").parsed.runtimeChecked
  assert(
    IO.readBytes(baseDirectory.value / actual).sameElements(IO.readBytes(baseDirectory.value / expected)),
    s"$actual no longer has the bytes of $expected"
  )
}

// A `-> hoconFormatCheck` line accepts any failure, so the suite names the one it pins: the count
// of unformatted files when a file needs formatting, the refusals when only a refusal fails it.
// Def.uncached: the task reads a Result, which sbt 2 refuses to cache.
InputKey[Unit]("assertCheckFailsSaying") := Def.uncached {
  val expected = Def.spaceDelimited("<words...>").parsed.mkString(" ")
  val message = hoconFormatCheck.result.value match {
    case sbt.Result.Inc(incomplete) => failedMessage(incomplete)
    case sbt.Result.Value(_)        => sys.error("expected hoconFormatCheck to fail")
  }
  assert(message.contains(expected), s"expected the failure to say '$expected', got: $message")
}

InputKey[Unit]("assertCheckFailsWithout") := Def.uncached {
  val unexpected = Def.spaceDelimited("<words...>").parsed.mkString(" ")
  val message = hoconFormatCheck.result.value match {
    case sbt.Result.Inc(incomplete) => failedMessage(incomplete)
    case sbt.Result.Value(_)        => sys.error("expected hoconFormatCheck to fail")
  }
  assert(!message.contains(unexpected), s"expected the failure not to say '$unexpected', got: $message")
}

TaskKey[Unit]("assertArtifactHome") := {
  val expected = file(sys.props("hocon.fmt.scripted.ivy.home")).getCanonicalFile
  // sbt 2's ivyHome is a String; sbt 1's is a File.
  assert(ivyPaths.value.ivyHome.exists(home => file(home).getCanonicalFile == expected))
  val location = eu.ww86.hoconfmt.sbt.HoconFormatterPlugin.getClass.getProtectionDomain.getCodeSource.getLocation.toString
  assert(location.contains(expected.toURI.toString.stripSuffix("/")), location)
}
TaskKey[Unit]("assertOtherFormatsUntouched") := {
  Seq("data.json", "app.properties").foreach { name =>
    assert(IO.read(baseDirectory.value / name) == "a = 1\n")
  }
}

// The message sbt recorded for a failed task, with the cause chain unwrapped.
def failedMessage(incomplete: sbt.Incomplete): String = {
  def from(cause: Throwable): Option[String] =
    Option(cause.getMessage).orElse(Option(cause.getCause).flatMap(from))
  incomplete.message.orElse(incomplete.directCause.flatMap(from)).getOrElse(incomplete.toString)
}
