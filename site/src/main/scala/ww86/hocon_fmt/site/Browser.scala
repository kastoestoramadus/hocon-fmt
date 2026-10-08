package ww86.hocon_fmt.site

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.Try
import scala.util.control.NonFatal

import org.scalajs.dom

/** The browser facilities the page needs, wrapped so no component touches a DOM API directly:
  * one kind of GET through `fetch`, one cache behind localStorage, today's date.
  */
object Browser:

  /** Long enough for the search on a slow connection, short enough that the state line does not
    * sit on "checking GitHub…" while the browser waits on a request that is going nowhere.
    */
  val searchDeadlineMs: Double = 8000

  // A GET through `fetch`, with every failure — no `fetch` at all included — a failed future, so
  // the refresh completes and the snapshot stands. A browser without `fetch` throws on the call
  // itself, which the `try` turns into that failure; nothing probes for the function, because a
  // typed native member read as a value is a Scala function object whose `typeof` is "object",
  // never "function".
  given GitHubApi.Http = withDeadline(searchDeadlineMs)

  /** The deadline is a parameter so a test can use one short enough to wait for. */
  private[site] def withDeadline(deadlineMs: Double): GitHubApi.Http = url =>
    try
      val controller = new dom.AbortController()
      val deadline   = js.timers.setTimeout(deadlineMs)(controller.abort())
      val request    = new dom.RequestInit { signal = controller.signal }
      dom.window
        .fetch(url, request)
        .toFuture
        .flatMap(response => response.text().toFuture.map(body => GitHubApi.Response(response.status, body)))
        .andThen { case _ => js.timers.clearTimeout(deadline) }
    catch case NonFatal(e) => Future.failed(e)

  // Storage is an optional browser facility: a page opened where it is denied works without it.
  given GitHubApi.Storage = new GitHubApi.Storage:
    def get(key: String): Option[String] =
      Try(Option(dom.window.localStorage.getItem(key))).getOrElse(None)
    def set(key: String, value: String): Unit =
      Try(dom.window.localStorage.setItem(key, value)).getOrElse(())

  /** Today as YYYY-MM-DD, the way the snapshot dates itself. */
  def today: String = new js.Date().toISOString().take(10)
