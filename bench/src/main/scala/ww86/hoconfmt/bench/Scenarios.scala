package ww86.hoconfmt.bench

/** Inputs to time the formatter on, generated so every run and every platform measures the same
  * text. Each stresses a different part of the pipeline.
  */
final case class Scenario(name: String, source: String)

object Scenarios {

  val all: List[Scenario] = List(
    Scenario("typical", typical),
    Scenario("large", sections(5000)),
    Scenario("includes", includes(300)),
    Scenario("comments", commented(2000)),
    Scenario("nested", nested(150)),
    Scenario("probe-family", probeFamily(30000))
  )

  /** A service's application.conf: what a pre-commit hook meets on most commits. */
  def typical: String =
    """# Service settings
      |include "defaults.conf"
      |service {
      |    name = "billing"
      |  port = 8080
      |  endpoints = [ "/a", "/b" ]
      |  timeouts { connect = 5s, read = 30s }
      |}
      |// Database
      |db {
      |  url = "jdbc:postgresql://localhost/billing"
      |  pool.size = 16
      |  user = ${?DB_USER}
      |}
      |features.flags = [ a, b, c ]
      |""".stripMargin

  def sections(count: Int): String =
    (0 until count).map { i =>
      s"section$i {\n  name = \"n$i\"\n  port = $i\n  tags = [a, b, c]\n}\n"
    }.mkString

  def includes(count: Int): String =
    (0 until count).map(i => s"include \"part$i.conf\"\nkey$i = $i\n").mkString

  def commented(count: Int): String =
    (0 until count).map(i => s"# setting $i explained\nkey$i = $i\n").mkString

  def nested(depth: Int): String =
    (0 until depth).map(i => s"k$i { ").mkString + "v = 1" + " }" * depth + "\n"

  /** A comment spelling the probe-prefix family, under an include so the probe runs. Finding the
    * first index no text spells must not rescan either text once per candidate index.
    */
  def probeFamily(count: Int): String =
    "include \"defaults.conf\"\n# " + (0 until count).map(i => s"__HOCON_MASK_${i}_").mkString(" ") + "\na = 1\n"
}
