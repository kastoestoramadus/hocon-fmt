package ww86.hocon_fmt

import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

import _root_.cats.effect.{ExitCode, IO}
import _root_.cats.syntax.all.*
import fs2.io.file.{Files, Path}
import fs2.{Chunk, Stream}

import ww86.hocon_fmt.CmdApi.Arguments

/** CLI behaviour on real files: exit codes, that every file is examined, and that a file the
  * formatter cannot handle is never overwritten. The same suite runs on the JVM, Scala Native
  * and Node.
  */
class CmdApiSpec extends munit.CatsEffectSuite {

  val tmp = ResourceFunFixture(Files[IO].tempDirectory)

  def write(dir: Path, name: String, bytes: Array[Byte]): IO[Path] =
    Stream.chunk(Chunk.array(bytes)).through(Files[IO].writeAll(dir / name)).compile.drain.as(dir / name)

  def write(dir: Path, name: String, text: String): IO[Path] = write(dir, name, text.getBytes(UTF_8))

  def bytesOf(file: Path): IO[List[Byte]] = Files[IO].readAll(file).compile.toList

  def textOf(file: Path): IO[String] = bytesOf(file).map(bytes => String(bytes.toArray, UTF_8))

  def check(files: Path*): IO[CmdApi.Run] =
    CmdApi
      .examineAll(Arguments(files.toList, checkOnly = true, config = None, style = StyleOverrides.none))
      .map(_.fold(e => fail(e), identity))

  def rewrite(files: Path*): IO[CmdApi.Run] =
    CmdApi
      .examineAll(Arguments(files.toList, checkOnly = false, config = None, style = StyleOverrides.none))
      .map(_.fold(e => fail(e), identity))

  def arguments(config: Option[Path] = None, style: StyleOverrides = StyleOverrides.none): Arguments =
    Arguments(files = Nil, checkOnly = false, config = config, style = style)

  val unformatted = "a   :    1"
  val formatted   = "a = 1\n"

  tmp.test("--check reports exit code 1 for an unformatted file") { dir =>
    write(dir, "a.conf", unformatted).flatMap(check(_)).map(run => assertEquals(run.exitCode, ExitCode(1)))
  }

  tmp.test("--check reports exit code 0 for an already formatted file") { dir =>
    write(dir, "a.conf", formatted).flatMap(check(_)).map(run => assertEquals(run.exitCode, ExitCode.Success))
  }

  tmp.test("--check leaves the file on disk untouched") { dir =>
    for {
      file <- write(dir, "a.conf", unformatted)
      _    <- check(file)
      text <- textOf(file)
    } yield assertEquals(text, unformatted)
  }

  // The defect: sys.exit fired from inside a parallel foreach, so the JVM died at the first
  // unformatted file and the remaining ones were never examined.
  tmp.test("--check examines every file, not just up to the first unformatted one") { dir =>
    for {
      files     <- (1 to 5).toList.traverse(i => write(dir, s"f$i.conf", unformatted))
      run       <- check(files*)
      realPaths <- files.traverse(Files[IO].realPath)
    } yield {
      assertEquals(run.exitCode, ExitCode(1))
      realPaths.foreach(path => assert(run.rendered.contains(path.toString), s"$path never reported:\n${run.rendered}"))
    }
  }

  tmp.test("write mode rewrites the file") { dir =>
    for {
      file <- write(dir, "a.conf", unformatted)
      run  <- rewrite(file)
      text <- textOf(file)
    } yield {
      assertEquals(run.exitCode, ExitCode.Success)
      assertEquals(text, formatted)
    }
  }

  // Safety: a file the formatter cannot handle must never be overwritten.
  tmp.test("write mode leaves an unformattable file untouched") { dir =>
    val broken = "a : ${"
    for {
      file <- write(dir, "a.conf", broken)
      run  <- rewrite(file)
      text <- textOf(file)
    } yield {
      assertEquals(text, broken)
      assertEquals(run.exitCode, ExitCode.Success)
      assert(run.rendered.contains("cannot format, leaving unchanged"), run.rendered)
    }
  }

  // A lenient decode turns each invalid byte into U+FFFD, and writing that back destroys it.
  tmp.test("write mode leaves a file that is not UTF-8 byte-for-byte untouched") { dir =>
    val latin2 = "a   :   \"³\"\n".getBytes(ISO_8859_1) // ł in ISO-8859-2
    for {
      file  <- write(dir, "a.conf", latin2)
      run   <- rewrite(file)
      bytes <- bytesOf(file)
    } yield {
      assertEquals(bytes, latin2.toList)
      assert(run.rendered.contains("cannot format, leaving unchanged"), run.rendered)
    }
  }

  // Lightbend's loader reads .json and .properties too, and a round trip hands back HOCON with
  // the objects reordered, not the file its name promises: the name alone decides, however valid
  // the content is as HOCON.
  tmp.test("write mode leaves a file named as another format untouched") { dir =>
    val json = "{\n    \"b\": 1,\n    \"a\": 2\n}\n"
    for {
      file <- write(dir, "application.json", json)
      run  <- rewrite(file)
      text <- textOf(file)
    } yield {
      assertEquals(text, json)
      assert(run.rendered.contains("cannot format, leaving unchanged"), run.rendered)
      assert(run.rendered.contains("a JSON file, and hocon-fmt formats HOCON only"), run.rendered)
    }
  }

  tmp.test("a file that cannot be read is reported without stopping the others") { dir =>
    for {
      file <- write(dir, "a.conf", unformatted)
      run  <- rewrite(dir / "missing.conf", file)
      text <- textOf(file)
    } yield {
      assert(run.rendered.contains("missing.conf"), run.rendered)
      assertEquals(text, formatted)
    }
  }

  // Examined twice, one file would be written by two fibers at once.
  tmp.test("a file named twice, however spelled, is examined once") { dir =>
    for {
      file <- write(dir, "a.conf", unformatted)
      run  <- rewrite(file, file, dir / "." / "a.conf")
      text <- textOf(file)
    } yield {
      assertEquals(run.outcomes.size, 1, run.rendered)
      assertEquals(text, formatted)
    }
  }

  test("arguments: files, with --check or -c") {
    assertEquals(
      CmdApi.command.parse(List("--check", "a.conf", "b.conf")),
      Right(
        CmdApi.Invocation.FileMode(
          Arguments(List(Path("a.conf"), Path("b.conf")), checkOnly = true, config = None, style = StyleOverrides.none)
        )
      )
    )
    assertEquals(
      CmdApi.command.parse(List("-c", "a.conf")),
      Right(
        CmdApi.Invocation.FileMode(
          Arguments(List(Path("a.conf")), checkOnly = true, config = None, style = StyleOverrides.none)
        )
      )
    )
    assertEquals(
      CmdApi.command.parse(List("a.conf")),
      Right(
        CmdApi.Invocation.FileMode(
          Arguments(List(Path("a.conf")), checkOnly = false, config = None, style = StyleOverrides.none)
        )
      )
    )
  }

  test("arguments: at least one file is required") {
    assert(CmdApi.command.parse(Nil).isLeft)
  }

  test("arguments: stdin and version need no files") {
    assertEquals(CmdApi.command.parse(List("--stdin")), Right(CmdApi.Invocation.Stdin("<stdin>", StyleOverrides.none)))
    assertEquals(
      CmdApi.command.parse(List("--stdin", "--stdin-filename", "editor.conf")),
      Right(CmdApi.Invocation.Stdin("editor.conf", StyleOverrides.none))
    )
    assertEquals(CmdApi.command.parse(List("--version")), Right(CmdApi.Invocation.Version))
  }

  test("arguments: reject ambiguous modes and orphaned stdin filename") {
    List(
      List("--stdin", "a.conf"),
      List("--stdin", "--check"),
      List("--version", "a.conf"),
      List("--version", "--stdin"),
      List("--stdin-filename", "editor.conf", "a.conf")
    ).foreach(args => assert(CmdApi.command.parse(args).isLeft, args.toString))
  }

  test("stdin formats without mixing reports into stdout") {
    val result = CmdApi.formatStdin(unformatted.getBytes(UTF_8), "editor.conf", StyleOverrides.none)
    assertEquals(result.stdout, formatted)
    assertEquals(result.stderr, "")
    assertEquals(result.exitCode, ExitCode.Success)
  }

  test("stdin preserves already formatted input") {
    assertEquals(CmdApi.formatStdin(formatted.getBytes(UTF_8), "<stdin>", StyleOverrides.none).stdout, formatted)
  }

  test("stdin refusal emits no output and fails, naming the input on stderr") {
    List("a: ${".getBytes(UTF_8), Array(0xff.toByte)).foreach { bytes =>
      val result = CmdApi.formatStdin(bytes, "editor.conf", StyleOverrides.none)
      assertEquals(result.stdout, "")
      assertEquals(result.exitCode, ExitCode(1))
      assert(result.stderr.contains("editor.conf"), result.stderr)
    }
  }

  // Lightbend's loader reads .json and .properties too; a round trip hands back HOCON, not the
  // file its name promises, so the name alone decides.
  test("stdin under a name that promises another format is refused, naming what it is") {
    val result = CmdApi.formatStdin("""{"a": 1}""".getBytes(UTF_8), "application.json", StyleOverrides.none)
    assertEquals(result.stdout, "")
    assertEquals(result.exitCode, ExitCode(1))
    assert(result.stderr.contains("application.json"), result.stderr)
    assert(result.stderr.contains("JSON"), result.stderr)
  }

  // --- the style flags and the repository's .hocon-fmt.conf ------------------------------------

  def styleFor(config: Option[Path] = None, style: StyleOverrides = StyleOverrides.none)(files: Path*) =
    CmdApi.styleFor(files.toList, arguments(config, style).copy(files = files.toList))

  test("arguments: --separator and --config parse, and the booleans come in --no- pairs") {
    assertEquals(
      CmdApi.command.parse(List("--separator", ":", "a.conf")),
      Right(
        CmdApi.Invocation.FileMode(
          Arguments(
            List(Path("a.conf")),
            checkOnly = false,
            config = None,
            style = StyleOverrides(separator = Some(Separator.Colon))
          )
        )
      )
    )
    assertEquals(
      CmdApi.command.parse(List("--config", "team.conf", "--separator", "=", "-c", "a.conf")),
      Right(
        CmdApi.Invocation.FileMode(
          Arguments(
            List(Path("a.conf")),
            checkOnly = true,
            config = Some(Path("team.conf")),
            style = StyleOverrides(separator = Some(Separator.Equals))
          )
        )
      )
    )
    assertEquals(
      CmdApi.command.parse(List("--double-indent", "--no-simplify-nested-objects", "a.conf")),
      Right(
        CmdApi.Invocation.FileMode(
          Arguments(
            List(Path("a.conf")),
            checkOnly = false,
            config = None,
            style = StyleOverrides(doubleIndent = Some(true), simplifyNestedObjects = Some(false))
          )
        )
      )
    )
  }

  test("arguments: a separator or flag pair nothing can honour is a usage error") {
    List(
      List("--separator", "equals", "a.conf"),
      List("--double-indent", "--no-double-indent", "a.conf"),
      List("--stdin", "--config", "team.conf"),
      List("--version", "--separator", ":")
    ).foreach(args => assert(CmdApi.command.parse(args).isLeft, args.toString))
  }

  test("--help lists the style flags and the config file") {
    val Left(help) = CmdApi.command.parse(List("--help")): @unchecked
    assert(help.errors.isEmpty, help.errors.toString)
    val text = help.toString
    List("--separator", "--config", "--double-indent", "--no-double-indent", "--simplify-nested-objects").foreach {
      flag =>
        assert(text.contains(flag), text)
    }
  }

  tmp.test("a .hocon-fmt.conf beside the file sets the style") { dir =>
    for {
      _      <- write(dir, ".hocon-fmt.conf", "separator = \":\"\n")
      file   <- write(dir, "a.conf", unformatted)
      styled <- styleFor()(file)
    } yield assertEquals(
      styled,
      Right(List(file -> FormatOptions(Separator.Colon, doubleIndent = false, simplifyNestedObjects = true)))
    )
  }

  tmp.test("the config file is looked up above the file's directory, and the nearest one wins") { dir =>
    val home = dir / "repo"
    for {
      _      <- Files[IO].createDirectory(home)
      _      <- write(home, ".hocon-fmt.conf", "separator = \":\"\n")
      sub     = home / "sub"
      _      <- Files[IO].createDirectory(sub)
      _      <- write(sub, ".hocon-fmt.conf", "double-indent = true\n")
      file   <- write(sub, "a.conf", unformatted)
      styled <- styleFor()(file)
    } yield assertEquals(
      styled,
      Right(List(file -> FormatOptions(Separator.Equals, doubleIndent = true, simplifyNestedObjects = true)))
    )
  }

  tmp.test("the walk stops at the repository root, so a style above the checkout does not reach in") { dir =>
    val repo = dir / "repo"
    for {
      _      <- write(dir, ".hocon-fmt.conf", "separator = \":\"\n")
      _      <- Files[IO].createDirectory(repo)
      _      <- Files[IO].createDirectory(repo / ".git")
      file   <- write(repo, "a.conf", unformatted)
      styled <- styleFor()(file)
    } yield assertEquals(styled, Right(List(file -> FormatOptions.default)))
  }

  tmp.test("a flag overrides the config file, the config file the default") { dir =>
    for {
      _        <- write(dir, ".hocon-fmt.conf", "separator = \":\"\n")
      file     <- write(dir, "a.conf", unformatted)
      fromFile <- styleFor()(file)
      byFlag   <- styleFor(style = StyleOverrides(separator = Some(Separator.Equals)))(file)
    } yield {
      assertEquals(
        fromFile,
        Right(List(file -> FormatOptions(Separator.Colon, doubleIndent = false, simplifyNestedObjects = true)))
      )
      assertEquals(byFlag, Right(List(file -> FormatOptions.default)))
    }
  }

  tmp.test("an unknown key is an error naming the config file and the key") { dir =>
    for {
      _      <- write(dir, ".hocon-fmt.conf", "separators = \":\"\n")
      file   <- write(dir, "a.conf", unformatted)
      styled <- styleFor()(file)
    } yield assert(styled.left.exists(_.startsWith(dir.toString)), styled)
  }

  tmp.test("a bad value is an error naming the config file and the key") { dir =>
    for {
      _      <- write(dir, ".hocon-fmt.conf", "double-indent = maybe\n")
      file   <- write(dir, "a.conf", unformatted)
      styled <- styleFor()(file)
    } yield assert(
      styled.left.exists(message => message.contains("double-indent") && message.contains(dir.toString)),
      styled
    )
  }

  tmp.test("an explicit --config is used for every file, nearer .hocon-fmt.conf files aside") { dir =>
    val sub = dir / "sub"
    for {
      _      <- write(dir, "team.conf", "separator = \":\"\n")
      _      <- write(dir, ".hocon-fmt.conf", "double-indent = true\n")
      _      <- Files[IO].createDirectory(sub)
      a      <- write(dir, "a.conf", unformatted)
      b      <- write(sub, "b.conf", unformatted)
      styled <- styleFor(config = Some(dir / "team.conf"))(a, b)
    } yield assertEquals(
      styled,
      Right(
        List(
          a -> FormatOptions(Separator.Colon, doubleIndent = false, simplifyNestedObjects = true),
          b -> FormatOptions(Separator.Colon, doubleIndent = false, simplifyNestedObjects = true)
        )
      )
    )
  }

  tmp.test("a missing explicit config file is an error naming it") { dir =>
    for {
      file   <- write(dir, "a.conf", unformatted)
      styled <- styleFor(config = Some(dir / "nope.conf"))(file)
    } yield assert(styled.left.exists(_.contains("nope.conf")), styled)
  }

  tmp.test("write mode formats in the style the config file asks for") { dir =>
    for {
      _    <- write(dir, ".hocon-fmt.conf", "separator = \":\"\n")
      file <- write(dir, "a.conf", "a = 1\n")
      run  <- rewrite(file)
      text <- textOf(file)
    } yield {
      assertEquals(run.exitCode, ExitCode.Success)
      assertEquals(text, "a: 1\n")
    }
  }

  tmp.test("--check counts a default-formatted file as unformatted when the style asks for :") { dir =>
    for {
      _    <- write(dir, ".hocon-fmt.conf", "separator = \":\"\n")
      file <- write(dir, "a.conf", "a = 1\n")
      run  <- check(file)
    } yield assertEquals(run.exitCode, ExitCode(1))
  }

  test("stdin applies the style flags; no config file is looked up, the stdin name is not read") {
    val result =
      CmdApi.formatStdin("a = 1\n".getBytes(UTF_8), "<stdin>", StyleOverrides(separator = Some(Separator.Colon)))
    assertEquals(result.stdout, "a: 1\n")
  }
}
