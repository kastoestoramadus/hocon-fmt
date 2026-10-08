import java.nio.file.Files

TaskKey[Unit]("prepare") := {
  val target = (baseDirectory.value / "src/main/resources/app.conf").toPath
  Files.createDirectories(target.getParent)
  Files.writeString(target, "a   =   1\n")
  if (target.getFileSystem.supportedFileAttributeViews.contains("unix")) {
    Files.setAttribute(target, "unix:mode", Int.box(Integer.parseInt("2750", 8)))
    Files.createLink((baseDirectory.value / "old-content").toPath, target)
    Files.createSymbolicLink(target.resolveSibling("alias.conf"), target.getFileName)
  }
}
TaskKey[Unit]("verify") := {
  val target = (baseDirectory.value / "src/main/resources/app.conf").toPath
  assert(Files.readString(target) == "a = 1\n")
  if (target.getFileSystem.supportedFileAttributeViews.contains("unix")) {
    assert(Files.readString((baseDirectory.value / "old-content").toPath) == "a   =   1\n")
    assert(Files.isSymbolicLink(target.resolveSibling("alias.conf")))
    assert((Files.getAttribute(target, "unix:mode").asInstanceOf[Integer].intValue & 0xfff) == Integer.parseInt("2750", 8))
  }
}
