package ww86.hocon_fmt.site

import scala.concurrent.Future
import scala.scalajs.js
import scala.util.Try

/** The one kind of network call the page makes: the read-only search for the author's pull
  * requests, with a small cache so reloading does not burn the unauthenticated rate limit of ten
  * searches a minute. No token, and no token belongs in a public page.
  */
object GitHubApi:

  final case class Response(status: Int, body: String)
  type Http = String => Future[Response]

  /** A failed `Future` needs a `Throwable`, so the two failures carry their detail as one. */
  enum GitHubError(detail: String) extends RuntimeException(detail):
    case Http(status: Int)     extends GitHubError(s"HTTP $status")
    case Malformed(detail: String) extends GitHubError(detail)

  def searchUrl(repo: String): String = ???

  /** The author's pull requests of one repository. Any non-200 and any malformed body fail; a
    * malformed item inside a well-formed body is merely dropped, by `LivePr.read`.
    */
  def authorPrs(repo: String)(using http: Http): Future[List[LivePr]] = ???

  /** The `items` of a search/issues response body. Fails when the body is not JSON or carries no
    * array; tolerates a malformed item by dropping it.
    */
  def parse(body: String): Either[GitHubError.Malformed, List[LivePr]] = ???

  // --- the cache -------------------------------------------------------------------------------

  /** Reloads within this window reuse the stored answer. */
  val cacheTtlMs: Double = 10 * 60 * 1000

  /** Stored as JSON so a guarded `try` and a fresh page can both read it back. */
  def encode(items: List[LivePr], fetchedAtMs: Double): String = ???

  /** `None` when the entry is stale, malformed, or not JSON at all. */
  def decode(cached: String, nowMs: Double): Option[List[LivePr]] = ???

  /** localStorage, hidden behind two methods so the tests can supply a fake — and so a page
    * opened where storage is unavailable formats the same, only without a cache.
    */
  trait Storage:
    def get(key: String): Option[String]
    def set(key: String, value: String): Unit

  def readCache(storage: Storage, key: String, nowMs: Double): Option[List[LivePr]] = ???

  def writeCache(storage: Storage, key: String, nowMs: Double, items: List[LivePr]): Unit = ???
