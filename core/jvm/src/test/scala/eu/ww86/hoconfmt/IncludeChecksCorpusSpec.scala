package eu.ww86.hoconfmt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class IncludeChecksCorpusSpec extends munit.FunSuite with IncludeChecksDifferentialChecks {
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(20, "minutes")
  val root                                                      = List(new File("."), new File("../.."))
    .find(f => new File(f, "build.sbt").isFile)
    .getOrElse(sys.error("cannot locate repository fixtures"))

  // Generated outputs, VCS data and dependency installs are excluded; malformed configs still
  // exercise the formatter verdict, while direct checks run whenever the masked parse succeeds.
  def files(directory: File): List[File] = Option(directory.listFiles()).toList.flatten.toList.flatMap { f =>
    if (f.isDirectory && !Set("target", ".git", "node_modules")(f.getName)) files(f)
    else if (f.isFile && f.getName.endsWith(".conf")) List(f)
    else Nil
  }
  def checkFiles(directory: File): Unit = {
    val corpus = files(directory).sortBy(_.getPath)
    assert(corpus.nonEmpty)
    corpus.foreach(file => checkIncludeChecks(String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)))
    println(s"include differential: ${corpus.size} configs under ${directory.getPath}")
  }
  test("include checks agree on every repository conf fixture") { checkFiles(root) }
  test("include checks agree on the optional external corpus") {
    sys.env.get("HOCON_INCLUDE_CORPUS") match {
      case Some(path) => checkFiles(new File(path))
      case None       => println("external corpus not requested: set HOCON_INCLUDE_CORPUS")
    }
  }
}
