// Both subprojects own `shared`, the way the platforms of a crossProject share resources.
lazy val sharedResources = Compile / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "shared"

lazy val a    = project.settings(sharedResources)
lazy val b    = project.settings(sharedResources)
lazy val root = (project in file(".")).aggregate(a, b)

// sbt keeps the log of each task's last run, which lets the script check what a task reported.
InputKey[Unit]("assertWarnedOnce") := {
  val task +: fragments = Def.spaceDelimited("<task> <fragment>...").parsed
  val lines = IO.readLines(target.value / "streams" / "_global" / task / "_global" / "streams" / "out")
  fragments.foreach { fragment =>
    val mentions = lines.filter(_.contains(fragment))
    assert(
      mentions.size == 1 && mentions.head.startsWith("[warn]"),
      s"expected one warning naming $fragment in the log of $task, got:\n${lines.mkString("\n")}"
    )
  }
}

// A file whose projects set different options must fail the task, naming both projects.
TaskKey[Unit]("assertSharedFileConflict") := {
  val message = hoconFormatCheck.result.value match {
    case Inc(incomplete) => failedMessage(incomplete)
    case Value(_) =>
      sys.error("expected hoconFormatCheck to refuse a shared file whose projects set different options")
  }
  Seq("projects a and b", "separator = :", "shared.conf").foreach { fragment =>
    assert(message.contains(fragment), s"expected the conflict to name $fragment, got: $message")
  }
}

// The message sbt recorded for a failed task, with the cause chain unwrapped.
def failedMessage(incomplete: sbt.Incomplete): String = {
  def from(cause: Throwable): Option[String] =
    Option(cause.getMessage).orElse(Option(cause.getCause).flatMap(from))
  incomplete.message.orElse(incomplete.directCause.flatMap(from)).getOrElse(incomplete.toString)
}
