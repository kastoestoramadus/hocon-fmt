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
