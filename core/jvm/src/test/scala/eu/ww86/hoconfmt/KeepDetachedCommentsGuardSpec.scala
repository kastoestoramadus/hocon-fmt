package eu.ww86.hoconfmt

import org.ekrich.config.{ConfigFactory, ConfigParseOptions}

/** The signal that the project page can go back to released sconfig. Red by design, like
  * `SconfigDefectsSpec` (run it with `sbt libraryDefects`): it asks released sconfig for the
  * option the page's fork carries, `setKeepDetachedComments` (ekrich/sconfig#646, draft #647), and
  * checks that it keeps a header above a blank line. JVM only, by reflection, because the method
  * does not exist to compile against until a release has it.
  *
  * UPSTREAM-SCONFIG: when this turns green, return to upstream sconfig; the steps are "Returning
  * to upstream sconfig" in docs/site.md. Delete this file with the rest.
  */
class KeepDetachedCommentsGuardSpec extends munit.FunSuite with HoconTestSupport {

  test("library: sconfig keeps a comment a blank line detaches from the field below") {
    val setter = classOf[ConfigParseOptions].getMethods
      .find(_.getName == "setKeepDetachedComments")
      .getOrElse(fail("sconfig has no setKeepDetachedComments yet (ekrich/sconfig#647)"))
    val options = setter.invoke(ConfigParseOptions.defaults, java.lang.Boolean.TRUE) match {
      case o: ConfigParseOptions => o
      case other                 => fail(s"unexpected result: $other")
    }
    val rendered = ConfigFactory.parseString("# banner\n\na : 1", options).root.render(HoconFormatter.renderOptions)
    assert(rendered.contains("banner"), rendered)
  }
}
