package ww86.hocon_fmt

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import org.ekrich.config.{ConfigFactory, ConfigRenderOptions}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** PROBE harness, JVM only, not for merging. Runs the formatter over a directory of `.conf` files
  * and writes one JSON line per file with the verdict and, for anything it formats, the checks
  * that matter: idempotence, comments in order, and the resolved value against the input's.
  *
  * {{{
  * ProbeRun behave
  * ProbeRun roundtrip <file.conf>
  * ProbeRun corpus <mode: off|comments|blanks> <files.txt> <out.jsonl> <outdir|->
  * }}}
  * `files.txt` lists absolute paths, one per line.
  */
object ProbeRun {

  private val jsonRender = ConfigRenderOptions.defaults
    .setJson(true)
    .setComments(false)
    .setOriginComments(false)
    .setFormatted(false)

  def main(args: Array[String]): Unit = args.toList match {
    case "behave" :: Nil                       => behave()
    case "roundtrip" :: file :: Nil            => roundtrip(file)
    case "maskdebug" :: file :: Nil            => maskdebug(file)
    case "meaning" :: file :: Nil              => meaning(file)
    case "unstable" :: file :: Nil             => unstable(file)
    case "corpus" :: mode :: list :: out :: dir :: Nil =>
      sys.props.update(ProbeMasking.ModeProperty, mode)
      corpus(mode, list, out, dir)
    case other => println(s"usage: behave | roundtrip <f> | maskdebug <f> | corpus <mode> <files.txt> <out.jsonl> <outdir|->, got $other")
  }

  private def maskdebug(file: String): Unit = {
    val raw    = String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8)
    val inc    = IncludeMasking.mask(raw)
    val probe  = ProbeMasking.mask(inc.text)
    println(s"probe mode: ${Option(sys.props.get(ProbeMasking.ModeProperty)).flatten.getOrElse("<default>")}")
    println(s"== lines ==\n${ProbeMasking.debugLines(raw)}")
    println(s"== include-masked ==\n${inc.text}|END")
    println(s"== probe-masked ==\n${probe.text}|END  originals=${probe.originals}")
    Try(ConfigFactory.parseString(probe.text, HoconFormatter.parseOptions).root.render(HoconFormatter.renderOptions)) match {
      case scala.util.Success(rendered) =>
        println(s"== rendered ==\n$rendered|END")
        println(s"== unmasked ==\n${IncludeMasking.unmask(ProbeMasking.unmask(rendered, probe.originals), inc.originals)}|END")
      case scala.util.Failure(e) => println(s"== render threw == ${Option(e.getMessage).getOrElse(e.toString)}")
    }
  }

  /** What sconfig itself does with the shapes the probe has to classify. */
  private def behave(): Unit = {
    val shapes = List(
      "#c\na : 1",
      "#   spaced   \na : 1",
      "# c\n\na : 1",
      "# a\n# b\n\n# c\nx : 1",
      "a : 1\n# trailing",
      "o {\n  a : 1\n  # last\n}",
      "o {\n  a : 1 # inline\n}",
      "# only",
      "# one\n// two\n",
      "a : # between\n1",
      "a : [\n  # c\n  1\n]",
      "a : [\n  1,\n\n  2\n]",
      "a : 1\n\n\nb : 2",
      "include \"missing.conf\" # trailing"
    )
    shapes.foreach { raw =>
      val outcome = Try {
        val parsed = ConfigFactory.parseString(raw, HoconFormatter.parseOptions)
        if (parsed.isEmpty) "<empty>" else parsed.root.render(HoconFormatter.renderOptions)
      }.toEither.left.map(e => s"<throws ${Option(e.getMessage).getOrElse(e.toString).take(120)}>")
      println(s"--- input: ${raw.replace("\n", "\\n")}\n    sconfig: ${outcome.fold(identity, identity)}")
    }
  }

  private def meaning(file: String): Unit = {
    val raw  = String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8)
    val name = Paths.get(file).getFileName.toString
    Verdict.of(raw, name) match {
      case Verdict.NeedsFormatting(text) =>
        val a = resolved(IncludeMasking.mask(raw).text)
        val b = resolved(IncludeMasking.mask(text).text)
        println(s"in:  $a\nout: $b")
      case other => println(describe(other))
    }
  }

  private def unstable(file: String): Unit = {
    val raw  = String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8)
    val name = Paths.get(file).getFileName.toString
    Verdict.of(raw, name) match {
      case Verdict.Refused(Refusal.UnstableOutput) =>
        val options = HoconFormatter.parseOptions.setOriginDescription(name)
        val first   = formatPass(raw, options)
        val second  = first.flatMap(t => formatPass(t, options))
        (first, second) match {
          case (Right(a), Right(b)) =>
            val (la, lb) = (a.split('\n').toList, b.split('\n').toList)
            println(s"pass1 ${la.length} lines, pass2 ${lb.length} lines; first diffs:")
            println(la.zipAll(lb, "<none>", "<none>").filter(p => p._1 != p._2).take(12)
              .map { case (x, y) => s"  1: $x\n  2: $y" }.mkString("\n"))
            val _ = Files.write(Paths.get("/tmp/claude-1000/-home-walidus-IdeProjects-hocon-formatter--claude-worktrees-hocon-fmt-page-redesign-d7b34d/0d9c15ea-8d9e-4795-9296-8100c7dd2e7d/scratchpad/sc/pass1.out"), a.getBytes(StandardCharsets.UTF_8))
          case (Left(e), _) => println(s"pass1 refused: ${e.reason.take(200)}")
          case (_, Left(e)) => println(s"pass2 refused: ${e.reason.take(200)}")
        }
      case other => println(describe(other))
    }
  }

  // Mirror of HoconFormatter.formatOnce's first two steps for the probe's diffing; not a route
  // through the real pipeline, just a way to see the two passes.
  private def formatPass(source: String, options: org.ekrich.config.ConfigParseOptions): Either[Refusal, String] = {
    val masked = IncludeMasking.mask(source)
    val probe  = ProbeMasking.mask(masked.text)
    scala.util.Try(org.ekrich.config.ConfigFactory.parseString(probe.text, options).root.render(HoconFormatter.renderOptions)).toEither.left
      .map(e => Refusal.NotHocon(Option(e.getMessage).getOrElse(e.toString)))
      .map(r => IncludeMasking.unmask(ProbeMasking.unmask(r, probe.originals), masked.originals))
  }

  private def roundtrip(file: String): Unit = {
    val raw     = String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8)
    val started = System.currentTimeMillis()
    val verdict = Verdict.of(raw, Paths.get(file).getFileName.toString)
    println(s"verdict in ${System.currentTimeMillis() - started} ms: ${describe(verdict)}")
    verdict match {
      case Verdict.NeedsFormatting(text) => println("---\n" + text + "---")
      case _                             => ()
    }
  }

  private def corpus(mode: String, list: String, out: String, dir: String): Unit = {
    val paths    = Files.readAllLines(Paths.get(list)).asScala.toList.filter(_.nonEmpty)
    val writer   = Files.newBufferedWriter(Paths.get(out), StandardCharsets.UTF_8)
    val counters = collection.mutable.Map.empty[String, Int].withDefaultValue(0)

    paths.foreach { path =>
      val name = Paths.get(path).getFileName.toString
      val id   = name.stripSuffix(".conf")
      val raw  = String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
      val verdict = Try(Verdict.of(raw, name)).toEither.left
        .map(e => Verdict.Refused(Refusal.NotHocon(s"threw: ${Option(e.getMessage).getOrElse(e.toString)}")))
        .fold(identity, identity)
      val kind = describe(verdict)
      counters(kind.takeWhile(_ != ':')) += 1
      val fields = collection.mutable.LinkedHashMap[String, String](
        "id" -> id, "mode" -> mode, "verdict" -> kind
      )
      verdict match {
        case Verdict.NeedsFormatting(text) =>
          fields += "lines_in" -> raw.linesIterator.length.toString
          fields += "lines_out" -> text.linesIterator.length.toString
          fields ++= checks(raw, text)
          if (dir != "-") { val _ = Files.write(Paths.get(dir, s"$id.out"), text.getBytes(StandardCharsets.UTF_8)) }
        case Verdict.Refused(refusal) =>
          fields += "reason" -> refusal.reason.take(200)
        case Verdict.AlreadyFormatted => ()
      }
      val _ = writer.append(fields.map { case (k, v) => s""""$k":${quote(v)}""" }.mkString("{", ",", "}\n"))
    }
    val _ = writer.close()
    println(s"mode=$mode over ${paths.length} files:")
    counters.toVector.sortBy(_._1).foreach { case (k, n) => println(f"  $k%-22s $n%5d") }
  }

  /** For a file that formatted: second pass, comments, and meaning against the input. */
  private def checks(raw: String, formatted: String): List[(String, String)] = {
    val again    = Verdict.of(formatted, "second-pass.conf")
    val stable   = again match { case Verdict.AlreadyFormatted => "yes"; case other => "no:" + describe(other) }
    val comments = orderedCommentVerdict(raw, formatted)
    val meaning  = meaningVerdict(raw, formatted)
    List("idempotent" -> stable, "comments" -> comments, "meaning" -> meaning)
  }

  private def orderedCommentVerdict(raw: String, formatted: String): String = {
    val before = HoconText.comments(raw)
    val after  = HoconText.comments(formatted)
    if (before == after) s"equal(${before.length})" else s"DIFFER(${before.length}->${after.length})"
  }

  /** Resolved values compared as rendered JSON. Includes are masked on both sides first: the
    * renderer cannot carry them, and resolving would reach for a filesystem that says nothing.
    */
  private def meaningVerdict(raw: String, formatted: String): String =
    (resolved(IncludeMasking.mask(raw).text), resolved(IncludeMasking.mask(formatted).text)) match {
      case (Right(a), Right(b)) if a == b => "same"
      case (Right(_), Right(_))           => "MISMATCH"
      case (Left(a), Left(b)) if a == b   => "both-fail"
      case (Left(a), Left(b))             => s"fail-differs:$a|$b"
      case (Left(a), Right(_))            => s"in-fails:$a"
      case (Right(_), Left(b))            => s"out-fails:$b"
    }

  private def resolved(text: String): Either[String, String] =
    Try(
      ConfigFactory
        .parseString(text, HoconFormatter.parseOptions)
        .resolve()
        .root
        .render(jsonRender)
    ).toEither.left.map(e => Option(e.getMessage).getOrElse(e.toString).take(200))

  private def describe(verdict: Verdict): String = verdict match {
    case Verdict.AlreadyFormatted                   => "already-formatted"
    case Verdict.NeedsFormatting(_)                 => "needs-formatting"
    case Verdict.Refused(_: Refusal.NotHocon)       => "refused:not-hocon"
    case Verdict.Refused(_: Refusal.LostComment)    => "refused:lost-comment"
    case Verdict.Refused(_: Refusal.LostInclude)    => "refused:lost-include"
    case Verdict.Refused(_: Refusal.MovedInclude)   => "refused:moved-include"
    case Verdict.Refused(_: Refusal.BrokenOutput)   => "refused:broken-output"
    case Verdict.Refused(Refusal.UnstableOutput)    => "refused:unstable-output"
    case Verdict.Refused(Refusal.NotUtf8)           => "refused:not-utf8"
    case Verdict.Refused(_: Refusal.OtherFormat)    => "refused:other-format"
  }

  private def quote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
}
