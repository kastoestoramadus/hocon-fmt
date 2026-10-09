package ww86.hoconfmt.site

/** The build's mark on the page: "deployed <UTC date-time> · <short sha>", the sha linking to its
  * commit. The values are baked in by the build — the Pages workflow passes what it deployed to
  * `sbt site/build` as environment variables, and build.sbt puts them into `BuildInfo`; the browser
  * asks nobody. A build told nothing, or only half of it, is a local build.
  */
object DeployStamp {

  /** What precedes the sha on the page, and the sha to link: its short form and its commit URL. */
  final case class Stamp(lead: String, sha: Option[(String, String)]) derives CanEqual

  val local: Stamp = Stamp("local build", None)

  def of(sha: Option[String], time: Option[String]): Stamp = (clean(sha), clean(time)) match {
    case (Some(fullSha), Some(builtAt)) =>
      Stamp(s"deployed $builtAt · ", Some(fullSha.take(7) -> Repo.commit(fullSha)))
    case _ => local
  }

  private def clean(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)
}
