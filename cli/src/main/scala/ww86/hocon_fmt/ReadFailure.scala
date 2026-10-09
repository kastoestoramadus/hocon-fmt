package ww86.hocon_fmt

/** Why a file could not be read, in words a user can act on: the exception for a missing file
  * carries only the file's name, the Node build reports filesystem failures as errno strings, and
  * Scala.js has no `java.nio.file` exceptions to match on. The common failures get fixed words;
  * anything else keeps the message it came with.
  */
object ReadFailure {

  /** The reason to report for a read that failed. */
  def message(e: Throwable): String =
    describe(e).getOrElse(Option(e.getMessage).getOrElse(e.toString).take(120))

  private def describe(e: Throwable): Option[String] = {
    val text = Option(e.getMessage).getOrElse("")
    val name = e.getClass.getName
    e match {
      // The class name, not the type: on Scala.js these classes do not exist to match on, and the
      // JVM's own `getMessage` for a missing file is only the path.
      case _ if name.endsWith("NoSuchFileException") || name.endsWith("FileNotFoundException") => Some("no such file")
      case _ if name.endsWith("AccessDeniedException")                                         => Some("access denied")
      case _ if text.contains("ENOENT") || text.contains("no such file")                       => Some("no such file")
      case _ if text.contains("EACCES") || text.contains("EPERM")                              =>
        Some("access denied")
      case _ if text.contains("EISDIR") || text.toLowerCase.contains("is a directory") => Some("is a directory")
      case _: java.io.IOException                                                      => Some(text.take(120))
      case _                                                                           => None
    }
  }
}
