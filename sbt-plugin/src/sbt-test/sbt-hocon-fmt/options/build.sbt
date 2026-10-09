TaskKey[Unit]("assertWarning") := {
  val log = IO.read(target.value / "streams" / "_global" / "hoconFormat" / "_global" / "streams" / "out")
  assert(log.contains("defined again"), log)
}
