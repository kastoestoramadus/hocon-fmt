package ww86.hocon_fmt.site

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.Try

import org.scalajs.dom

/** The browser facilities the page needs, wrapped so no component touches a DOM API directly:
  * one kind of GET through `fetch`, one cache behind localStorage, today's date.
  */
object Browser:

  given GitHubApi.Http = url =>
    dom.window
      .fetch(url)
      .toFuture
      .flatMap(response => response.text().toFuture.map(body => GitHubApi.Response(response.status, body)))

  // Storage is an optional browser facility: a page opened where it is denied works without it.
  given GitHubApi.Storage = new GitHubApi.Storage:
    def get(key: String): Option[String] =
      Try(Option(dom.window.localStorage.getItem(key))).getOrElse(None)
    def set(key: String, value: String): Unit =
      Try(dom.window.localStorage.setItem(key, value)).getOrElse(())

  /** Today as YYYY-MM-DD, the way the snapshot dates itself. */
  def today: String = new js.Date().toISOString().take(10)
