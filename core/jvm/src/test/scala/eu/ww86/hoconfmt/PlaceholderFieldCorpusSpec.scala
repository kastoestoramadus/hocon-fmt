package eu.ww86.hoconfmt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The include-mask scan is ours; corpus configs exercise it without upstream parsing. */
class PlaceholderFieldCorpusSpec extends munit.FunSuite with PlaceholderFieldDifferentialChecks {
  val root = List(new File("."), new File("../.."))
    .find(f => new File(f, "build.sbt").isFile)
    .getOrElse(sys.error("cannot locate repository fixtures"))

  // Build outputs and VCS data are excluded; every checked-in .conf/.hocon is eligible,
  // including metadata, golden outputs, integration fixtures and research probes.
  def files(directory: File): List[File] = Option(directory.listFiles()).toList.flatten.toList.flatMap { f =>
    if (f.isDirectory && !Set("target", ".git", "node_modules")(f.getName)) files(f)
    else if (f.isFile && (f.getName.endsWith(".conf") || f.getName.endsWith(".hocon"))) List(f)
    else Nil
  }

  test("placeholder finding agrees with the old scan on every repository config fixture") {
    val corpus = files(root).sortBy(_.getPath)
    assert(corpus.nonEmpty)
    corpus.foreach { file =>
      val source = String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
      checkInputs(List(source))
    }
    println(s"placeholder differential: ${corpus.size} repository files, ${corpus.size * 3} scans")
  }
}
