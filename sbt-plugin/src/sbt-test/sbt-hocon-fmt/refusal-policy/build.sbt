TaskKey[Unit]("assertUntouched") := {
  assert(IO.readBytes(baseDirectory.value / "src/main/resources/refused.conf")
    .sameElements(IO.readBytes(baseDirectory.value / "original.conf")))
}
