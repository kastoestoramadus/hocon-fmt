package ww86.hocon_fmt.site

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import ww86.hocon_fmt.site.GitHubApi.{GitHubError, Http, Response}

/** The fetch path against a fake: success, a rate limit, a malformed body, and the cache that
  * keeps a reload from burning the ten-searches-a-minute limit.
  */
class GitHubApiSpec extends munit.FunSuite {

  val body =
    """{"total_count":2,"items":[
      |{"number":642,"title":"property tests","state":"open","pull_request":{"merged_at":null}},
      |{"number":598,"title":"renderer","state":"closed","pull_request":{"merged_at":"2026-09-30T17:48:35Z"}},
      |{"number":617,"title":"MiMa","state":"closed","pull_request":{"merged_at":null}},
      |{"number":17,"title":"an issue, not a pull request","state":"open"},
      |{"title":"no number","state":"open","pull_request":{"merged_at":null}}
      |]}""".stripMargin

  test("the search URL asks for the author's pull requests of the repository, a hundred at most") {
    val url = GitHubApi.searchUrl("ekrich/sconfig")
    assert(url.startsWith("https://api.github.com/search/issues?"), url)
    assert(url.contains("author:kastoestoramadus"), url)
    assert(url.contains("type:pr"), url)
    assert(url.contains("repo:ekrich/sconfig"), url)
    assert(url.contains("per_page=100"), url)
  }

  test("a 200 body yields the pull requests, issues and malformed items dropped") {
    GitHubApi.authorPrs("ekrich/sconfig")(using ok(body)).map { items =>
      assertEquals(
        items,
        List(
          LivePr(642, "property tests", LiveState.Open),
          LivePr(598, "renderer", LiveState.Merged),
          LivePr(617, "MiMa", LiveState.ClosedUnmerged)
        )
      )
    }
  }

  test("a rate limit or any other non-200 fails, it does not empty the list") {
    GitHubApi
      .authorPrs("ekrich/sconfig")(using ok(body, status = 403))
      .map(items => fail(s"expected a failure, got $items"))
      .recover { case GitHubError.Http(403) => () }
  }

  test("a malformed body fails instead of yielding nothing") {
    val broken = List("not json at all", "{}", "{\"items\":null}", "{\"items\":{}}")
    broken.foreach { text =>
      assert(GitHubApi.parse(text).isLeft, s"expected [$text] to be rejected")
    }
  }

  test("null search items are dropped while valid pull requests survive") {
    assertEquals(GitHubApi.parse("""{"items":[null]}"""), Right(Nil))
    assertEquals(GitHubApi.parse(body.replace("[", "[null,")), GitHubApi.parse(body))
  }

  test("null cache items are dropped while valid pull requests survive") {
    assertEquals(GitHubApi.decode("""{"fetchedAt":1000,"items":[null]}""", nowMs = 1000), Some(Nil))
    val items  = List(LivePr(598, "renderer", LiveState.Merged))
    val stored = GitHubApi.encode(items, fetchedAtMs = 1000)
    assertEquals(GitHubApi.decode(stored.replace("[", "[null,"), nowMs = 1000), Some(items))
  }

  test("the cache round-trips and expires") {
    val items  = List(LivePr(598, "renderer", LiveState.Merged))
    val stored = GitHubApi.encode(items, fetchedAtMs = 1000)
    assertEquals(GitHubApi.decode(stored, nowMs = 1000 + GitHubApi.cacheTtlMs - 1), Some(items))
    assertEquals(GitHubApi.decode(stored, nowMs = 1000 + GitHubApi.cacheTtlMs), None)
    assertEquals(GitHubApi.decode("not json", nowMs = 2000), None)
    assertEquals(GitHubApi.decode("{\"fetchedAt\":1}", nowMs = 2000), None)
  }

  test("an unavailable or broken storage is survived: reads and writes degrade to no cache") {
    val throwing = new GitHubApi.Storage {
      def get(key: String): Option[String]      = throw new RuntimeException("storage unavailable")
      def set(key: String, value: String): Unit = throw new RuntimeException("storage unavailable")
    }

    assertEquals(GitHubApi.readCache(throwing, "k", nowMs = 1), None)
    GitHubApi.writeCache(throwing, "k", nowMs = 1, items = Nil) // must not throw
  }

  test("a written cache answers within the window and goes stale after it") {
    val memory  = scala.collection.mutable.Map.empty[String, String]
    val storage = new GitHubApi.Storage {
      def get(key: String): Option[String]      = memory.get(key)
      def set(key: String, value: String): Unit = memory(key) = value
    }

    val items = List(LivePr(598, "renderer", LiveState.Merged))
    GitHubApi.writeCache(storage, "sconfig", nowMs = 1000, items = items)
    assertEquals(GitHubApi.readCache(storage, "sconfig", nowMs = 1000 + GitHubApi.cacheTtlMs - 1), Some(items))
    assertEquals(GitHubApi.readCache(storage, "sconfig", nowMs = 1000 + GitHubApi.cacheTtlMs), None)
  }

  def ok(text: String, status: Int = 200): Http = _ => Future.successful(Response(status, text))
}
