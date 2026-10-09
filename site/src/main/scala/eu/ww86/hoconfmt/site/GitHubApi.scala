package eu.ww86.hoconfmt.site

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.Try
import scala.util.control.NonFatal

/** The one kind of network call the page makes: the read-only search for the author's pull
  * requests, with a small cache so reloading does not burn the unauthenticated rate limit of ten
  * searches a minute. No token, and no token belongs in a public page.
  */
object GitHubApi {

  final case class Response(status: Int, body: String)
  type Http = String => Future[Response]

  /** A failed `Future` needs a `Throwable`, so both failures carry their detail as one. */
  enum GitHubError(detail: String) extends RuntimeException(detail) {
    case Http(status: Int)         extends GitHubError(s"HTTP $status")
    case Malformed(detail: String) extends GitHubError(detail)
  }

  def searchUrl(repo: String): String =
    s"https://api.github.com/search/issues?q=author:kastoestoramadus+type:pr+repo:$repo&per_page=100"

  /** The author's pull requests of one repository. Any non-200 and any malformed body fail; a
    * malformed item inside a well-formed body is merely dropped, by `LivePr.read`.
    */
  def authorPrs(repo: String)(using http: Http): Future[List[LivePr]] =
    http(searchUrl(repo)).flatMap { response =>
      if response.status != 200 then Future.failed(GitHubError.Http(response.status))
      else
        parse(response.body) match {
          case Right(items) => Future.successful(items)
          case Left(detail) => Future.failed(detail)
        }
    }

  /** The `items` of a search/issues response body. Fails when the body is not JSON or carries no
    * array; tolerates a malformed item by dropping it.
    */
  def parse(body: String): Either[GitHubError, List[LivePr]] =
    Try(js.JSON.parse(body)).toEither.left
      .map(t => GitHubError.Malformed(Option(t.getMessage).getOrElse("unreadable response")))
      .flatMap { json =>
        LivePr
          .asArray(json.selectDynamic("items"))
          .map(_.flatMap(LivePr.read).toList)
          .toRight(GitHubError.Malformed("no items array"))
      }

  // --- the cache -------------------------------------------------------------------------------

  /** Reloads within this window reuse the stored answer. */
  val cacheTtlMs: Double = 10 * 60 * 1000

  /** Stored as JSON so a guarded `try` and a fresh page can both read it back. */
  def encode(items: List[LivePr], fetchedAtMs: Double): String = {
    val itemsJson = items
      .map(pr => s"""{"number":${pr.number},"title":${js.JSON.stringify(pr.title)},"state":"${stateName(pr.state)}"}""")
      .mkString(",")
    s"""{"fetchedAt":$fetchedAtMs,"items":[$itemsJson]}"""
  }

  /** `None` when the entry is stale, malformed, or not JSON at all. */
  def decode(cached: String, nowMs: Double): Option[List[LivePr]] =
    Try(js.JSON.parse(cached)).toOption.flatMap { json =>
      for
        fetchedAt <- LivePr.asDouble(json.selectDynamic("fetchedAt"))
        if fetchedAt + cacheTtlMs > nowMs
        items <- LivePr.asArray(json.selectDynamic("items"))
      yield items.flatMap(readCachedItem).toList
    }

  /** The cache stores the same fields the live answer carries, state spelled out. A malformed
    * entry invalidates only itself.
    */
  private def readCachedItem(item: js.Dynamic): Option[LivePr] =
    if js.isUndefined(item) || Option(item).isEmpty then None
    else
      for
        number <- LivePr.asInt(item.number)
        title  <- LivePr.asString(item.title)
        state  <- LivePr.asString(item.state).flatMap {
                   case "open"   => Some(LiveState.Open)
                   case "merged" => Some(LiveState.Merged)
                   case "closed" => Some(LiveState.ClosedUnmerged)
                   case _        => None
                 }
      yield LivePr(number, title, state)

  private def stateName(state: LiveState): String = state match {
    case LiveState.Open           => "open"
    case LiveState.Merged         => "merged"
    case LiveState.ClosedUnmerged => "closed"
  }

  /** localStorage, hidden behind two methods so the tests can supply a fake — and so a page
    * opened where storage is unavailable formats the same, only without a cache.
    */
  trait Storage {
    def get(key: String): Option[String]
    def set(key: String, value: String): Unit
  }

  def readCache(storage: Storage, key: String, nowMs: Double): Option[List[LivePr]] =
    try storage.get(key).flatMap(decode(_, nowMs))
    catch case NonFatal(_) => None

  def writeCache(storage: Storage, key: String, nowMs: Double, items: List[LivePr]): Unit =
    try storage.set(key, encode(items, nowMs))
    catch case NonFatal(_) => ()
}
