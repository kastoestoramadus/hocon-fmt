TaskKey[Unit]("assertWarning") := {
  val log = IO.read(target.value / "streams" / "_global" / "hoconFormat" / "_global" / "streams" / "out")
  assert(log.contains("defined again"), log)
}
TaskKey[Unit]("assertFixtureBytes") := {
  assert(IO.readBytes(baseDirectory.value / "src/main/resources/app.conf").sameElements(
    IO.readBytes(baseDirectory.value / "expected.conf")))
}
