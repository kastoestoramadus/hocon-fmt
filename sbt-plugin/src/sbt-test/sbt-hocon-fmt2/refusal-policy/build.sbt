TaskKey[Unit]("assertUntouched") := {
  assert(IO.readBytes(baseDirectory.value / "src/main/resources/refused.conf")
    .sameElements(IO.readBytes(baseDirectory.value / "original.conf")))
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
