TaskKey[Unit]("assertWarning") := {
  val log = IO.read(target.value / "streams" / "_global" / "hoconFormat" / "_global" / "streams" / "out")
  assert(log.contains("defined again"), log)
}
TaskKey[Unit]("assertFixtureBytes") := {
  assert(IO.readBytes(baseDirectory.value / "src/main/resources/app.conf").sameElements(
    IO.readBytes(baseDirectory.value / "expected.conf")))
}

TaskKey[Unit]("prepareAlias") := {
  val outside = baseDirectory.value / "outside"
  IO.createDirectory(outside)
  IO.write(outside / ".hocon-fmt.conf", "separator = \"=\"\n")
  IO.write(outside / "target.conf", "a=1\n")
  val alias = baseDirectory.value / "src/main/resources/alias.conf"
  java.nio.file.Files.createSymbolicLink(alias.toPath, (outside / "target.conf").toPath)
  ()
}
TaskKey[Unit]("assertAliasStyle") := {
  assert(IO.read(baseDirectory.value / "src/main/resources/alias.conf") == "a: 1\n")
}

// A rejected separator must name the setting and the value, whichever value broke it.
InputKey[Unit]("assertBadSeparator") := {
  val value = Def.spaceDelimited("<value>").parsed.mkString(" ")
  val message = hoconFormat.result.value match {
    case Inc(incomplete) => failedMessage(incomplete)
    case Value(_)        => sys.error("expected hoconFormat to reject the separator")
  }
  assert(message.contains("separator"), s"expected the failure to name separator, got: $message")
  assert(message.contains("got: " + value), s"expected the failure to name the value $value, got: $message")
}

// The message sbt recorded for a failed task, with the cause chain unwrapped.
def failedMessage(incomplete: sbt.Incomplete): String = {
  def from(cause: Throwable): Option[String] =
    Option(cause.getMessage).orElse(Option(cause.getCause).flatMap(from))
  incomplete.message.orElse(incomplete.directCause.flatMap(from)).getOrElse(incomplete.toString)
}
