package eu.ww86.hoconfmt

import java.nio.charset.StandardCharsets.UTF_8

/** The contract of the command line, checked on a real process of each runtime. */
class CliAcceptanceSuite extends munit.FunSuite {

  private def property(name: String): String =
    sys.props
      .get(name)
      .collect { case value: String => value }
      .getOrElse(sys.error(s"$name is not set; run this suite through sbt acceptance/test"))

  private val runtimes: List[(String, Seq[String])] = List(
    "jvm" -> Seq(
      s"${sys.props("java.home")}/bin/java",
      "-cp",
      property("cli.jvm.classpath"),
      "eu.ww86.hoconfmt.CmdApi"
    ),
    "node"   -> Seq("node", property("cli.node.main")),
    "native" -> Seq(property("cli.native.binary"))
  )

  private val unformatted = "name   =   \"zażółć gęślą jaźń ✓\"\nb { c=1 }\n"
  private val formatted   = "name = \"zażółć gęślą jaźń ✓\"\nb.c = 1\n"
  // Multi-byte characters must survive the 4096-byte reads that stdin is consumed in.
  private val large = (1 to 1500).map(i => s"key$i = \"zażółć gęślą ✓\"\n").mkString

  final private case class Result(exitCode: Int, stdout: Array[Byte], stderr: String) {
    def stdoutText: String = String(stdout, UTF_8)
  }

  // JDK 24+ warns about sun.misc.Unsafe from scala-library on every JVM start; that is not the formatter's.
  private def own(stderr: String): String =
    stderr.linesIterator.filterNot(_.startsWith("WARNING: ")).mkString("\n")

  /** `cli --stdin < in > out 2> err`: all three are regular files, as a shell redirect makes them. */
  private def redirected(cli: Seq[String], locale: String, input: Array[Byte], args: String*): Result = {
    val dir = os.temp.dir()
    os.write(dir / "in", input)
    val code = os
      .proc(cli, args)
      .call(
        stdin = dir / "in",
        stdout = dir / "out",
        stderr = dir / "err",
        env = Map("LC_ALL" -> locale, "LANG" -> locale),
        check = false
      )
      .exitCode
    Result(code, os.read.bytes(dir / "out"), own(os.read(dir / "err")))
  }

  /** `printf … | cli --stdin`, the way an editor attaches it. */
  private def piped(cli: Seq[String], locale: String, input: Array[Byte], args: String*): Result = {
    val r = os
      .proc(cli, args)
      .call(stdin = input, stderr = os.Pipe, env = Map("LC_ALL" -> locale, "LANG" -> locale), check = false)
    Result(r.exitCode, r.out.bytes, own(r.err.text()))
  }

  def reportGolden: String = {
    val relative = os.RelPath("cli/src/test/resources/reports/process.json")
    List(os.pwd / relative, os.pwd / os.up / relative)
      .find(os.exists(_))
      .fold(fail("cannot locate process JSON golden"))(os.read(_))
  }

  for ((runtime, cli) <- runtimes) {
    test(s"[$runtime] JSON report destinations preserve formatting and strict exit codes") {
      val version = os.proc(cli, "--version").call().out.text().stripPrefix("hocon-fmt ").trim
      List("-", "report.json").foreach { target =>
        val dir = os.temp.dir()
        os.write(dir / "app.conf", "a=1\n")
        os.write(dir / "data.json", "a = 1\n")
        val result = os
          .proc(cli, "--report", target, "--fail-on-refusal", "app.conf", "data.json")
          .call(cwd = dir, stderr = os.Pipe, check = false)
        assertEquals(result.exitCode, 1)
        val expected = os
          .read(os.pwd / "cli/src/test/resources/reports/process.json")
          .replace("@VERSION@", version)
          .replace("@PATH@", (dir / "data.json").toString)
        assertEquals(if (target == "-") result.out.text() else os.read(dir / "report.json"), expected)
        assertEquals(os.read(dir / "app.conf"), "a = 1\n")
        assertEquals(os.read(dir / "data.json"), "a = 1\n")
      }
    }

    test(s"[$runtime] JSON configuration failures retain selected counts and existing bytes") {
      val dir = os.temp.dir()
      os.write(dir / "app.conf", "a=1\n")
      os.write(dir / "bad.cfg", "typo = true\n")
      val result = os
        .proc(cli, "--report", "-", "--config", "bad.cfg", "app.conf", "app.conf")
        .call(cwd = dir, stderr = os.Pipe, check = false)
      assertEquals(result.exitCode, 2)
      assert(result.out.text().contains("\"checked\":1,\"changed\":0,\"refused\":[]"))
      assert(
        result.out.text().contains("\"failed\":[{\"path\":\"bad.cfg\",\"message\":\"bad.cfg: unknown option: typo\"}]")
      )
      assertEquals(os.read(dir / "app.conf"), "a=1\n")
    }

    test(s"[$runtime] report output failures preserve exits and cannot overwrite refused input") {
      val dir = os.temp.dir()
      os.write(dir / "data.json", "a = 1\n")
      val blocked = os
        .proc(cli, "--report", "data.json", "--fail-on-refusal", "data.json")
        .call(cwd = dir, stderr = os.Pipe, check = false)
      assertEquals(blocked.exitCode, 1)
      assertEquals(os.read(dir / "data.json"), "a = 1\n")
      val failed = os
        .proc(cli, "--report", dir.toString, "data.json")
        .call(cwd = dir, stderr = os.Pipe, check = false)
      assertEquals(failed.exitCode, 0)
      assert(failed.err.text().contains("cannot write report"))
    }

    test(s"[$runtime] stdin supports a report file while retaining formatted stdout") {
      val dir    = os.temp.dir()
      val report = dir / "report.json"
      val result = os
        .proc(cli, "--stdin", "--stdin-filename", "editor.conf", "--report", report.toString)
        .call(stdin = "a=1\n", stderr = os.Pipe, check = false)
      assertEquals(result.exitCode, 0)
      assertEquals(result.out.text(), "a = 1\n")
      assert(os.read(report).contains("\"checked\":1,\"changed\":1,\"refused\":[],\"failed\":[]"))
    }

    for (locale <- List("C", "C.UTF-8")) {
      test(s"[$runtime] --stdin formats and writes UTF-8 under LC_ALL=$locale") {
        val r = redirected(cli, locale, unformatted.getBytes(UTF_8), "--stdin")
        assertEquals(r.exitCode, 0)
        assertEquals(r.stdoutText, formatted)
        assertEquals(r.stderr, "")
      }

      test(s"[$runtime] --stdin returns formatted input unchanged under LC_ALL=$locale") {
        assertEquals(redirected(cli, locale, formatted.getBytes(UTF_8), "--stdin").stdoutText, formatted)
      }

      test(s"[$runtime] --stdin handles input beyond one read under LC_ALL=$locale") {
        val r = redirected(cli, locale, large.getBytes(UTF_8), "--stdin")
        assertEquals(r.exitCode, 0)
        assertEquals(r.stdoutText, large)
      }

      test(s"[$runtime] --stdin on empty input prints nothing and succeeds under LC_ALL=$locale") {
        val r = redirected(cli, locale, Array.emptyByteArray, "--stdin")
        assertEquals(r.exitCode, 0)
        assertEquals(r.stdoutText, "")
      }
    }

    test(s"[$runtime] --stdin accepts a pipe") {
      val r = piped(cli, "C", unformatted.getBytes(UTF_8), "--stdin")
      assertEquals(r.exitCode, 0)
      assertEquals(r.stdoutText, formatted)
    }

    test(s"[$runtime] a refusal prints nothing on stdout, names the input on stderr and exits 1") {
      val r = redirected(cli, "C", "a: ${".getBytes(UTF_8), "--stdin", "--stdin-filename", "editor.conf")
      assertEquals(r.exitCode, 1)
      assertEquals(r.stdoutText, "")
      assert(r.stderr.contains("editor.conf"), r.stderr)
    }

    test(s"[$runtime] input that is not UTF-8 is refused without output") {
      val r = redirected(cli, "C", Array(0xff.toByte, 0xfe.toByte), "--stdin")
      assertEquals(r.exitCode, 1)
      assertEquals(r.stdoutText, "")
    }

    test(s"[$runtime] a missing file exits 2 and is named on stderr, not stdout") {
      val dir = os.temp.dir()
      val r   = os
        .proc(cli, "--check", "nope.conf")
        .call(cwd = dir, stdout = os.Pipe, stderr = os.Pipe, env = Map("LC_ALL" -> "C", "LANG" -> "C"), check = false)
      assertEquals(r.exitCode, 2)
      val err = own(r.err.text())
      assert(err.contains("cannot read"), err)
      assert(err.contains("nope.conf"), err)
      assert(err.contains("no such file"), err)
      assert(!r.out.text().contains("nope.conf"), r.out.text())
    }

    test(s"[$runtime] a directory argument is walked and --check names the files inside") {
      val dir = os.temp.dir()
      os.makeDir.all(dir / "nested")
      os.write(dir / "a.conf", unformatted)
      os.write(dir / "b.txt", unformatted)
      os.write(dir / "nested" / "c.hocon", unformatted)
      val r = os
        .proc(cli, "--check", dir)
        .call(cwd = dir, stdout = os.Pipe, stderr = os.Pipe, env = Map("LC_ALL" -> "C", "LANG" -> "C"), check = false)
      assertEquals(r.exitCode, 1)
      val out = r.out.text()
      assert(out.contains("a.conf"), out)
      assert(out.contains("c.hocon"), out)
      assert(!out.contains("b.txt"), out)
    }

    test(s"[$runtime] --version prints the version") {
      val r = redirected(cli, "C", Array.emptyByteArray, "--version")
      assertEquals(r.exitCode, 0)
      assert(r.stdoutText.matches("""hocon-fmt \d.*\s*"""), r.stdoutText)
    }

    for (
      args <- List(
                List("--stdin", "a.conf"),
                List("--stdin", "--check"),
                List("--version", "a.conf"),
                List("--stdin-filename", "x.conf"),
                Nil
              )
    ) {
      test(s"[$runtime] arguments '${args.mkString(" ")}' exit 2 with nothing on stdout") {
        val r = redirected(cli, "C", Array.emptyByteArray, args*)
        assertEquals(r.exitCode, 2)
        assertEquals(r.stdoutText, "")
      }
    }
  }
}
