import sbt._
import Keys._

/** Everything behind the `Global / libraryDefects` task except the task itself: the test listener
  * that records what a suite run reported, the verdict drawn from that record plus the task's own
  * outcome, and the labelled one-line-per-suite report. It lives in `project/` because a `def` in
  * build.sbt cannot see a class defined in the same file.
  */
object LibraryDefects {
  val dirName = "library-defects"

  def recordDir(target: File): File = new File(target, dirName)

  /** `dir` is the record directory itself, `recordDir(target)`. */
  def recordFile(dir: File, suite: String): File = new File(dir, suite.split('.').last + ".txt")

  /** Counts of one suite's run, as `DefectRecorder` records them. */
  final case class SuiteRecord(
      throwable: Option[String],
      passed: Int,
      failed: Int,
      errors: Int,
      ignored: Int,
      skipped: Int,
      canceled: Int,
      pending: Int
  ) {
    def red: Int   = failed + errors
    def total: Int = passed + failed + errors + ignored + skipped + canceled + pending

    /** Counts add up; the throwable is not part of a merge, it is decided by how the suite ended. */
    def +(other: SuiteRecord): SuiteRecord =
      copy(
        passed = passed + other.passed,
        failed = failed + other.failed,
        errors = errors + other.errors,
        ignored = ignored + other.ignored,
        skipped = skipped + other.skipped,
        canceled = canceled + other.canceled,
        pending = pending + other.pending
      )
  }

  /** `None` when the file is absent, which is how the verdict tells that the suite never reported
    * anything — the failure happened before the test run.
    */
  def readRecord(file: File): Option[SuiteRecord] =
    if (!file.exists) None
    else {
      val props = IO
        .readLines(file)
        .map { line =>
          val i = line.indexOf('=')
          if (i <= 0) "" -> "" else line.take(i) -> line.drop(i + 1)
        }
        .toMap
      def count(key: String): Int = props.get(key).flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(0)
      Some(
        SuiteRecord(
          props.get("throwable"),
          count("passed"),
          count("failed"),
          count("errors"),
          count("ignored"),
          count("skipped"),
          count("canceled"),
          count("pending")
        )
      )
    }

  private def toRecord(counts: SuiteResult): SuiteRecord =
    SuiteRecord(
      None,
      counts.passedCount,
      counts.failureCount,
      counts.errorCount,
      counts.ignoredCount,
      counts.skippedCount,
      counts.canceledCount,
      counts.pendingCount
    )

  /** Writes one record per suite into `target/library-defects`: the per-test counts, or the
    * throwable when the suite crashed instead of completing. The verdict tells "the suite ran and
    * failed" (a red by design) from "the build failed before the suite ran" (a compile error, a
    * missing Node or clang) by this record, so the two are not reported the same way.
    */
  final private class DefectRecorder(recordDir: File) extends TestReportListener {
    private val emptyRecord = SuiteRecord(None, 0, 0, 0, 0, 0, 0, 0)
    // Suites of one test run can interleave, so counts are kept per suite while it is open. A
    // munit event names the test, not the suite: its fully qualified name only *starts with* the
    // suite's, which is how a batch is attributed. Mutable state because `TestReportListener` is
    // a callback interface; only the test run writes it.
    private val open = scala.collection.mutable.Map.empty[String, SuiteRecord]

    override def startGroup(name: String): Unit    = synchronized { open(name) = emptyRecord }
    override def testEvent(event: TestEvent): Unit = synchronized {
      val counts = SuiteResult(event.detail)
      val batch  = toRecord(counts)
      if (batch.total > 0) {
        val head = event.detail.headOption.map(_.fullyQualifiedName).getOrElse("")
        val name = open.keys.find(head.startsWith).orElse(if (open.size == 1) open.keys.headOption else None)
        name.foreach(n => open(n) = open.getOrElse(n, emptyRecord) + batch)
      }
    }
    override def endGroup(name: String, t: Throwable): Unit = synchronized {
      open.remove(name)
      write(
        name,
        Seq(
          s"suite=$name",
          "throwable=" + s"${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}".replaceAll("[\r\n]+", " ")
        )
      )
    }
    override def endGroup(name: String, result: TestResult): Unit = synchronized {
      val r = open.remove(name).getOrElse(emptyRecord)
      write(
        name,
        Seq(
          s"suite=$name",
          s"ended=$result",
          s"passed=${r.passed}",
          s"failed=${r.failed}",
          s"errors=${r.errors}",
          s"ignored=${r.ignored}",
          s"skipped=${r.skipped}",
          s"canceled=${r.canceled}",
          s"pending=${r.pending}"
        )
      )
    }
    private def write(suite: String, lines: Seq[String]): Unit =
      IO.write(recordFile(recordDir, suite), lines.mkString("", "\n", "\n"))
  }

  /** Attaches the recorder a project's library-defect suites write their records through. Config
    * level, so `testOnly` picks it up like `test` does.
    */
  def recordDefectRun: Setting[?] =
    Test / testListeners += new DefectRecorder(recordDir(target.value))

  /** Deletes the record the named suite would write. The suite's `testOnly` waits on this task, so
    * a record left by an earlier run cannot survive a failure that happens before this run's suite
    * reports — that failure must stay a broken build, not look like a red or a green.
    */
  def cleanRecord(platform: String, suite: String): Def.Initialize[Task[Unit]] = Def.task {
    IO.delete(recordFile(recordDir((ThisBuild / baseDirectory).value / "core" / platform / "target"), suite))
    ()
  }

  sealed trait Verdict
  object Verdict {
    final case class Red(red: Int, total: Int) extends Verdict
    final case class Green(total: Int)         extends Verdict
    final case class Broken(why: String)       extends Verdict

    /** One labelled line per platform and suite: without the label the reader cannot tell the JVM
      * run from the Scala.js or Scala Native one, since the suites print the same suite names.
      */
    def line(label: String, verdict: Verdict): String = verdict match {
      case Red(red, total) => s"$label: $red of $total red"
      case Green(total)    => s"$label: green (0 of $total red)"
      case Broken(why)     => s"$label: did not reach a test result (${why.take(160)})"
    }
  }

  def describeIncomplete(inc: Incomplete): String = {
    val cause = inc.directCause.orElse(inc.causes.headOption)
    cause match {
      case Some(t) => s"${t.getClass.getName}: ${Option(t.getMessage).getOrElse("(no message)")}"
      case None    => "no direct cause recorded; see the errors above"
    }
  }

  /** A test failure is red by design; a compile error, a missing runtime or a crashed suite is a
    * build failure and gets its own message instead of the red-by-design one.
    */
  def classify(taskFailure: Option[String], record: Option[SuiteRecord]): Verdict =
    (record, taskFailure) match {
      case (Some(SuiteRecord(Some(crash), _, _, _, _, _, _, _)), _) =>
        Verdict.Broken(s"the suite crashed: $crash")
      case (Some(r), _) if r.total == 0 =>
        Verdict.Broken("the suite reported no tests")
      case (Some(r), _) if r.red > 0 =>
        Verdict.Red(r.red, r.total)
      case (Some(r), None) =>
        Verdict.Green(r.total)
      case (Some(_), Some(why)) =>
        Verdict.Broken(s"the run failed after the suite reported no failures: $why")
      case (None, Some(why)) =>
        Verdict.Broken(
          s"the failure happened before any test ran (a compile error, a missing runtime or toolchain?): $why"
        )
      case (None, None) =>
        Verdict.Broken("no result was recorded although the run passed")
    }

  /** Prints one labelled line per suite, then fails when a suite is red by design — naming every
    * red — or when a suite could not reach a verdict, which is never a library defect.
    */
  def report(outcomes: Seq[(String, Result[Unit], File, String)], log: Logger): Unit = {
    val verdicts: Seq[(String, Verdict)] = outcomes.map { case (label, outcome, targetDir, suite) =>
      val taskFailure = outcome match {
        case Inc(why) => Some(describeIncomplete(why))
        case Value(_) => None
      }
      val verdict = classify(taskFailure, readRecord(recordFile(recordDir(targetDir), suite)))
      log.info(Verdict.line(label, verdict))
      label -> verdict
    }
    val broken = verdicts.collect { case (label, Verdict.Broken(why)) => s"$label: $why" }
    val reds   = verdicts.collect { case (label, Verdict.Red(red, total)) => s"$label: $red of $total red" }
    if (broken.nonEmpty)
      sys.error(
        "libraryDefects could not reach a verdict for every suite — this is not a red-by-design " +
          "library defect, the build itself failed. Fix the failure above and run it again:\n  " +
          broken.mkString("\n  ")
      )
    if (reds.nonEmpty)
      sys.error(
        "library defects remain on:\n  " + reds.mkString("\n  ") +
          "\nRed by design while the upstream bugs are open; see docs/limitations.md."
      )
    log.success(
      "All library-defect suites pass. An upstream fix has landed: drop the matching refusal in " +
        "HoconFormatter, its SconfigDefectsSpec case and its docs/limitations.md entry, and " +
        "recheck the UPSTREAM-SCONFIG notes."
    )
  }
}
