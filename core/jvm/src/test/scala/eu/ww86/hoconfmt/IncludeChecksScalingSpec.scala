package eu.ww86.hoconfmt

import java.lang.management.ManagementFactory

import org.ekrich.config.ConfigFactory

/** Eight times the input may pay for parsing and indexes; rescanning every field per include
  * grows by roughly sixty-four times. Thread CPU time excludes scheduler pauses and other suites'
  * GC pauses; the ratio guards algorithmic work rather than wall-clock load or allocation latency.
  */
class IncludeChecksScalingSpec extends munit.FunSuite {
  val threadCpu: java.lang.management.ThreadMXBean =
    Option(ManagementFactory.getThreadMXBean).getOrElse(fail("no thread CPU timer"))
  assert(threadCpu.isCurrentThreadCpuTimeSupported, "the scaling guard needs a JVM thread CPU timer")
  if (!threadCpu.isThreadCpuTimeEnabled) { threadCpu.setThreadCpuTimeEnabled(true) }

  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(5, "minutes")

  def checkScaling(
      name: String,
      check: (String, IncludeMasking.Masked, org.ekrich.config.ConfigObject, String) => Any
  ): Unit = {
    def task(n: Int): () => Any = {
      val source   = IncludeChecksDifferentialInputs.large(n)
      val masked   = IncludeMasking.mask(source)
      val root     = ConfigFactory.parseString(masked.text, HoconFormatter.parseOptions).root
      val rendered = root.render(HoconFormatter.renderOptions)
      () => check(source, masked, root, rendered)
    }
    val small = task(200)
    val large = task(1600)
    (0 until 5).foreach(_ => { val _ = small() })
    val _                              = large()
    def median(run: () => Any): Double = (0 until 3)
      .map { _ =>
        val start = threadCpu.getCurrentThreadCpuTime
        val _     = run()
        (threadCpu.getCurrentThreadCpuTime - start).toDouble / 1000000
      }
      .sorted
      .apply(1)
    val before = median(small)
    val after  = median(large)
    println(s"include scaling CPU $name: 200=$before ms, 1600=$after ms, ratio=${after / before}")
    assert(after < before * 24, s"$name grew ${after / before} times for eight times the includes (limit 24)")
  }

  // The moved-order scaling row is gone with IncludeOrder: the document engine keeps include
  // order structurally, so there is no per-include position walk left to bound.
  test("shadowed includes avoid rebuilding the object per include") {
    checkScaling("shadowed", (_, masked, root, _) => IncludeShadow.shadowed(masked.text, masked.originals, root))
  }
}
