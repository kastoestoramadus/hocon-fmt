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
