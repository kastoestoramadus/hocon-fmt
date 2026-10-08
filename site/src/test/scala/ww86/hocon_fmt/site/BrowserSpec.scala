package ww86.hocon_fmt.site

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.{Failure, Success}

import Browser.given

/** Reads a native member the way `org.scalajs.dom` declares `window.fetch`: as a typed member
  * rather than as a property of a `js.Dynamic`.
  */
@js.native
trait FetchGlobal extends js.Object:
  def fetch(url: String): js.Promise[js.Any] = js.native

/** The browser the page really runs in, faked: `dom.window` is one global lookup, so a test can
  * hand the `Browser` givens a `fetch` and a localStorage without jsdom. This is the part no unit
  * test covered when the page shipped, and the part that was broken — see the last test.
  */
class BrowserSpec extends munit.FunSuite:

  /** What api.github.com answers with: one pull request the snapshot knows (#600, open there) and
    * one it does not (#999).
    */
  val searchBody =
    """{"total_count":2,"items":[
      |{"number":600,"title":"merge entries","state":"closed","pull_request":{"merged_at":"2026-10-01T00:00:00Z"}},
      |{"number":999,"title":"brand new","state":"open","pull_request":{"merged_at":null}}
      |]}""".stripMargin

  override def afterEach(context: AfterEach): Unit =
    // Node has no window of its own; a fake left behind would be a trap for the next suite.
    install(js.Dynamic.literal())
    super.afterEach(context)

  test("the browser's own fetch answers the author's pull requests") {
    val browser = FakeBrowser()
    install(browser.window(Some(browser.answering(searchBody))))
    GitHubApi.authorPrs("ekrich/sconfig").map { prs =>
      assertEquals(prs.map(_.number).sorted, List(600, 999))
      assertEquals(browser.searched.toList, List(GitHubApi.searchUrl("ekrich/sconfig")))
    }
  }

  test("the refresh folds the live answer into the snapshot, dates it today, lists the unknown") {
    val browser = FakeBrowser()
    install(browser.window(Some(browser.answering(searchBody))))
    ContributionsView.refreshBoard.map { board =>
      assertEquals(board.liveLibraries, Library.values.toList)
      assertEquals(board.failedLibraries, Nil)
      assertEquals(board.asOf, Browser.today)
      assert(!board.checking, "the state line must not sit on 'checking GitHub…'")
      val known = board.entries.filter(_.library == Library.Sconfig).find(_.number == 600).get
      assertEquals(known.state, PrState.MergedUnreleased)
      assertEquals(board.entries.size, Contributions.all.size)
      assertEquals(board.others(Library.Sconfig).map(_.number), List(999))
    }
  }

  test("a second refresh within the cache window searches nothing again") {
    val browser = FakeBrowser()
    install(browser.window(Some(browser.answering(searchBody))))
    val twice = for
      first  <- ContributionsView.refreshBoard
      second <- ContributionsView.refreshBoard
    yield (first, second)
    twice.map { case (first, second) =>
      assertEquals(first.liveLibraries, Library.values.toList)
      // One request per repository for both refreshes together: the cache answered the second.
      assertEquals(browser.searched.size, Library.values.size)
      assertEquals(second.liveLibraries, Library.values.toList)
    }
  }

  test("a browser without fetch at all leaves the snapshot standing") {
    val browser = FakeBrowser()
    install(browser.window(None))
    ContributionsView.refreshBoard.map(assertSnapshotStands)
  }

  test("a search that fails leaves the snapshot standing") {
    val browser = FakeBrowser()
    install(browser.window(Some(js.Any.fromFunction1((_: String) => js.Promise.reject(new js.Error("network"))))))
    ContributionsView.refreshBoard.map(assertSnapshotStands)
  }

  test("a search that never answers is abandoned at its deadline, not waited on") {
    val browser = FakeBrowser()
    install(browser.window(Some(browser.neverAnswers)))
    Browser
      .withDeadline(50)("https://api.github.com/search/issues")
      .transform {
        case Failure(error) =>
          assert(error.getMessage.contains("deadline test: aborted"), s"unexpected fetch failure: $error")
          Success(())
        case Success(response) =>
          fail(s"expected the deadline to abort the request, got $response")
      }
  }

  test("a native member read as a value is never a function, which is why the probe is gone") {
    // `js.typeOf(dom.window.fetch)` is "object": Scala.js hands over a function object, not the
    // function. The shipped page probed exactly that and concluded every browser lacks fetch, so
    // the live refresh never ran. A fake window keeps this honest for the next reader.
    val window = js.Dynamic.literal(fetch = js.Any.fromFunction1((url: String) => js.Promise.resolve(url)))
    assertEquals(js.typeOf(window.selectDynamic("fetch")), "function")
    assert(js.typeOf(window.asInstanceOf[FetchGlobal].fetch) != "function")
  }

  def assertSnapshotStands(board: ContributionsView.Board): Unit =
    assertEquals(board.failedLibraries, Library.values.toList)
    assertEquals(board.liveLibraries, Nil)
    assertEquals(board.entries, Contributions.all)
    assertEquals(board.asOf, Contributions.readOn)
    assert(!board.checking, "an answer that is a failure must still settle the state line")

  def install(window: js.Any): Unit =
    js.Dynamic.global.globalThis.updateDynamic("window")(window)

  /** A window Node does not have: a recording `fetch` and a localStorage in memory. */
  final class FakeBrowser:
    val searched: scala.collection.mutable.ListBuffer[String] = scala.collection.mutable.ListBuffer.empty
    val store                                                 = scala.collection.mutable.Map.empty[String, String]

    def answering(body: String, status: Int = 200): js.Any = js.Any.fromFunction1 { (url: String) =>
      searched += url
      js.Promise.resolve(
        js.Dynamic.literal(status = status, text = js.Any.fromFunction0(() => js.Promise.resolve(body)))
      )
    }

    /** What a connection that has gone nowhere looks like: the promise settles only when the
      * page's own deadline aborts the request.
      */
    def neverAnswers: js.Any = js.Any.fromFunction2 { (_: String, request: js.Dynamic) =>
      new js.Promise[js.Any]((resolve, reject) => {
        val signal = request.selectDynamic("signal")
        assert(!js.isUndefined(signal), "fetch request must carry an abort signal")
        // Settle even if abort regresses, so the success branch fails promptly.
        val fallback = js.timers.setTimeout(500) {
          val _ = resolve(js.Dynamic.literal(status = 200, text = js.Any.fromFunction0(() => js.Promise.resolve(""))))
        }
        signal.addEventListener(
          "abort",
          js.Any.fromFunction0(() => {
            js.timers.clearTimeout(fallback)
            reject(new js.Error("deadline test: aborted"))
          })
        )
      })
    }

    def window(fetch: Option[js.Any]): js.Any =
      val storage = js.Dynamic.literal(
        getItem = js.Any.fromFunction1[String, String | Null](key => store.getOrElse[String | Null](key, null)),
        setItem = js.Any.fromFunction2 { (key: String, value: String) =>
          store.update(key, value)
          ()
        }
      )
      fetch match
        case Some(f) => js.Dynamic.literal(fetch = f, localStorage = storage)
        case None    => js.Dynamic.literal(localStorage = storage)
