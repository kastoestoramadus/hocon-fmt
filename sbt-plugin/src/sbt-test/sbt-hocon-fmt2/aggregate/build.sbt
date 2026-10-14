// Both subprojects own `shared`, the way the platforms of a crossProject share resources.
lazy val sharedResources = Compile / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "shared"

lazy val a    = project.settings(sharedResources)
lazy val b    = project.settings(sharedResources)
lazy val root = (project in file(".")).aggregate(a, b)

// sbt keeps the log of each task's last run, which lets the script check what a task reported.
InputKey[Unit]("assertWarnedOnce") := {
  val task +: fragments = Def.spaceDelimited("<task> <fragment>...").parsed.runtimeChecked
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
// Def.uncached: the task reads a Result, which sbt 2 refuses to cache.
TaskKey[Unit]("assertSharedFileConflict") := Def.uncached {
  val message = hoconFormatCheck.result.value match {
    case sbt.Result.Inc(incomplete) => failedMessage(incomplete)
    case sbt.Result.Value(_) =>
      sys.error("expected hoconFormatCheck to refuse a shared file whose projects set different options")
  }
  Seq("projects a and b", "separator = :", "shared.conf").foreach { fragment =>
    assert(message.contains(fragment), s"expected the conflict to name $fragment, got: $message")
  }
}

// Both assertions are about the check that covers a and b, which only the root's does; in a or b
// the check sees one owner per file and no conflict to name. sbt 1 runs each assertion at the
// root alone, while sbt 2 acts a task in every project that defines it, so both are pinned to the
// root the way hoconFormatCheck itself is.
InputKey[Unit]("assertWarnedOnce") / aggregate        := false
TaskKey[Unit]("assertSharedFileConflict") / aggregate := false

// The message sbt recorded for a failed task, with the cause chain unwrapped.
def failedMessage(incomplete: sbt.Incomplete): String = {
  def from(cause: Throwable): Option[String] =
    Option(cause.getMessage).orElse(Option(cause.getCause).flatMap(from))
  incomplete.message.orElse(incomplete.directCause.flatMap(from)).getOrElse(incomplete.toString)
}
