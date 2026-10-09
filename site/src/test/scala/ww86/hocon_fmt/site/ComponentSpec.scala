package ww86.hocon_fmt.site

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveElement
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

import ww86.hocon_fmt.{ExampleData, Verdict}

/** The components themselves, mounted into the fake document. `Playground.verdicts` and
  * `ContributionsView.boardUpdates` are covered elsewhere; what those tests cannot see is the
  * wiring in `apply` — which signal reaches which pane, and when a section goes looking for an
  * answer. The wiring this pins down (a verdict per keystroke, a refresh started while building
  * the section) once passed every other suite in this project.
  */
class ComponentSpec extends munit.FunSuite {

  val storyTitles = List(
    "Three people edited this file",
    "Set twice, only one counts",
    "Dev defaults, prod overrides",
    "A real library file",
    "A typo",
    "The safety net"
  )

  /** A GitHub that answers only when the test says so, and counts what was asked. */
  final class Github {
    val searched = scala.collection.mutable.ListBuffer.empty[String]
    val waiting  = scala.collection.mutable.ListBuffer.empty[() => Unit]
    val store    = scala.collection.mutable.Map.empty[String, String]

    def answer(): Unit = {
      val pending = waiting.toList
      waiting.clear()
      pending.foreach(_.apply())
    }

    def window(document: js.Dynamic): js.Dynamic = js.Dynamic.literal(
      document = document,
      fetch = js.Any.fromFunction2 { (url: String, _: js.Any) =>
        searched += url
        new js.Promise[js.Any]((resolve, _) => {
          waiting += (() => {
            val _ = resolve(
              js.Dynamic.literal(
                status = 200,
                text = js.Any.fromFunction0(() => js.Promise.resolve("""{"total_count":0,"items":[]}"""))
              )
            )
          })
        })
      },
      localStorage = js.Dynamic.literal(
        getItem = js.Any.fromFunction1[String, String | Null](key => store.getOrElse[String | Null](key, null)),
        setItem = js.Any.fromFunction2 { (key: String, value: String) =>
          store.update(key, value)
          ()
        }
      )
    )
  }

  override def afterEach(context: AfterEach): Unit = {
    // Node has no window or document of its own; a fake left behind would be a trap for the next suite.
    val global = js.Dynamic.global.globalThis
    global.updateDynamic("document")(js.undefined)
    global.updateDynamic("window")(js.undefined)
    super.afterEach(context)
  }

  /** Installs the fake browser and returns the container its body holds: a mounted element has to
    * be inside the fake document's tree, or Laminar refuses to render into it.
    */
  def install(github: Github): js.Dynamic = {
    val document = FakeDom.document()
    val global   = js.Dynamic.global.globalThis
    global.updateDynamic("document")(document)
    global.updateDynamic("window")(github.window(document))
    val container = document.createElement("div")
    val _         = document.selectDynamic("body").appendChild(container)
    container
  }

  def mount(container: js.Dynamic)(element: => HtmlElement): RootNode =
    render(container.asInstanceOf[dom.Element], element)

  def settle(ms: Int): Future[Unit] = {
    val done = Promise[Unit]()
    val _    = js.timers.setTimeout(ms) { done.success(()); () }
    done.future
  }

  def hasClass(name: String)(node: js.Dynamic): Boolean =
    node.className.asInstanceOf[String].split(" ").contains(name)

  def find(container: js.Dynamic, name: String): js.Dynamic =
    container
      .find(hasClass(name))
      .asInstanceOf[js.UndefOr[js.Dynamic]]
      .getOrElse(fail(s"""nothing with class "$name" under the mounted component"""))

  def findAll(container: js.Dynamic, name: String): List[js.Dynamic] =
    container.findAll(hasClass(name)).asInstanceOf[js.Array[js.Dynamic]].toList

  def children(node: js.Dynamic): List[js.Dynamic] =
    node.childNodes.asInstanceOf[js.Array[js.Dynamic]].toList

  def stateLine(container: js.Dynamic): String =
    find(container, "state-line").textContent.asInstanceOf[String]

  def statusElement(container: js.Dynamic): js.Dynamic =
    children(find(container, "status"))
      .find(_.nodeType.asInstanceOf[Int] == 1)
      .getOrElse(fail("the status line is empty"))

  /** The playground's two panes, in the page's order: input first, output second. */
  def panes(container: js.Dynamic): (js.Dynamic, js.Dynamic) =
    findAll(container, "conf") match {
      case input :: output :: Nil => (input, output)
      case areas                  => fail(s"expected two panes, found ${areas.size}")
    }

  def type_(pane: js.Dynamic, text: String): Unit = {
    pane.updateDynamic("value")(text)
    val _ = pane.fire("input")
  }

  /** What the page is supposed to show for a text: the core's own answer, not a second opinion. */
  def formatted(text: String): String = Verdict.of(text) match {
    case Verdict.NeedsFormatting(output) => output
    case _                               => text
  }

  // --- the playground --------------------------------------------------------------------------

  test("the playground formats the first example on the spot, and asks the network for nothing") {
    val github          = Github()
    val container       = install(github)
    val root            = mount(container)(Playground())
    val (input, output) = panes(container)
    val example         = ExampleData.showcase.headOption.fold("")(_.input)
    assertEquals(input.value.asInstanceOf[String], example)
    assertEquals(output.value.asInstanceOf[String], formatted(example))
    assert(find(container, "status").textContent.asInstanceOf[String].contains("Formatted"))
    assertEquals(github.searched.toList, Nil, "the playground runs the core in the page, not on a server")
    val _ = root.unmount()
  }

  test("a burst of typing settles on its last text and replaces the status once") {
    val github          = Github()
    val container       = install(github)
    val root            = mount(container)(Playground())
    val (input, output) = panes(container)
    val status          = statusElement(container)
    type_(input, "a = 2")
    type_(input, "a   =   3")
    settle(500).map { _ =>
      assertEquals(input.value.asInstanceOf[String], "a   =   3")
      assertEquals(output.value.asInstanceOf[String], formatted("a   =   3"))
      assert(!(statusElement(container) eq status), "a new text must replace the status")
      val _ = root.unmount()
      ()
    }
  }

  test("a burst that ends on the text already on screen leaves the status and the panes alone") {
    val github          = Github()
    val container       = install(github)
    val root            = mount(container)(Playground())
    val (input, output) = panes(container)
    val settled         = input.value.asInstanceOf[String]
    val status          = statusElement(container)
    type_(input, "a = 2")
    type_(input, settled)
    settle(500).map { _ =>
      assert(statusElement(container) eq status, "equal text must not recompute the verdict or rebuild the status")
      assertEquals(output.value.asInstanceOf[String], formatted(settled))
      val _ = root.unmount()
      ()
    }
  }

  test("an example button puts its example back into both panes") {
    val github          = Github()
    val container       = install(github)
    val root            = mount(container)(Playground())
    val (input, output) = panes(container)
    val buttons         = findAll(container, "example")
    val example         = ExampleData.showcase.headOption.fold("")(_.input)
    assertEquals(buttons.size, ExampleData.showcase.size + 5)
    type_(input, "[1, 2]\n")
    settle(500)
      .flatMap { _ =>
        assertEquals(output.value.asInstanceOf[String], "[1, 2]\n", "a refusal leaves the text as typed")
        val _ = buttons.head.fire("click")
        settle(500)
      }
      .map { _ =>
        assertEquals(input.value.asInstanceOf[String], example)
        assertEquals(output.value.asInstanceOf[String], formatted(example))
        val _ = root.unmount()
        ()
      }
  }

  test("the six stories show their formatted output, findings, and permanent refusals") {
    val container = install(Github())
    val root      = mount(container)(Playground())
    val buttons   = findAll(container, "example")
    assertEquals(buttons.take(6).map(_.textContent.asInstanceOf[String]), storyTitles)
    val (input, output) = panes(container)
    buttons.take(6).zipWithIndex
      .foldLeft(Future.successful(())) { case (done, (button, index)) =>
        done.flatMap { _ =>
          val _ = button.fire("click")
          settle(250).map { _ =>
            val example = ExampleData.showcase(index)
            assertEquals(input.value.asInstanceOf[String], example.input)
            assertEquals(output.value.asInstanceOf[String], formatted(example.input))
            val status = find(container, "status").textContent.asInstanceOf[String]
            assert(status.contains(if index < 4 then "Formatted" else "Left unchanged"), status)
            if index == 1 then {
              val finding = find(container, "findings").textContent.asInstanceOf[String]
              assert(finding.contains("service.port"), finding)
              assert(finding.contains("line 1") && finding.contains("line 3"), finding)
            }
            if index == 4 then assert(status.contains("playground: 4"), status)
            if index == 5 then assert(status.contains("Keep this operational note"), status)
            println(s"Story ${index + 1}: ${example.title}: $status")
          }
        }
      }
      .map { _ =>
        val _ = root.unmount(); ()
      }
  }

  def moreIds = List("messy", "includes", "comments", "not-hocon", "sconfig-defect")

  test("the five original examples follow the stories and every refusal explains preserved input") {
    val container = install(Github())
    val root = mount(container)(Playground())
    val originals = moreIds.map(id => ExampleData.all.find(_.id == s"catalogue/$id").get)
    val buttons = findAll(container, "example")
    assertEquals(buttons.drop(6).map(_.textContent.asInstanceOf[String]), originals.map(_.title))
    assert(find(container, "more-examples").textContent.asInstanceOf[String].contains("More examples"))
    val (input, output) = panes(container)
    buttons.zip(ExampleData.showcase ++ originals).foldLeft(Future.successful(())) {
      case (done, (button, example)) => done.flatMap { _ =>
        val _ = button.fire("click")
        settle(250).map { _ =>
          assertEquals(input.value.asInstanceOf[String], example.input)
          assertEquals(output.value.asInstanceOf[String], formatted(example.input))
          Verdict.of(example.input) match {
            case Verdict.Refused(_) =>
              val status = find(container, "status").textContent.asInstanceOf[String]
              assert(status.contains("Left unchanged"), status)
              assert(status.contains("Your input is unchanged"), status)
              assert(find(container, "refusal-detail").textContent.asInstanceOf[String].nonEmpty)
              val _ = find(container, "resolved-tab").fire("click")
              assertEquals(output.value.asInstanceOf[String], example.input)
              val _ = find(container, "formatted-tab").fire("click")
              println(s"Refusal ${example.id}: $status")
            case _ => ()
          }
        }
      }
    }.map { _ => val _ = root.unmount(); () }
  }

  test("upstream gaps show linked issue, our fix, and verified waiting state") {
    val container = install(Github())
    val root = mount(container)(Playground())
    val cases = List((2, "600", "waiting for maintainer review and merge"),
      (3, "647", "draft; waiting for the maintainer"),
      (10, "598", "merged upstream; waiting for a release"))
    cases.foldLeft(Future.successful(())) { case (done, (index, fix, state)) =>
      done.flatMap { _ =>
        val _ = findAll(container, "example")(index).fire("click")
        settle(250).map { _ =>
          val note = find(container, "upstream-note")
          val text = note.textContent.asInstanceOf[String]
          assert(text.contains("Published core"), text)
          assert(text.contains(s"#$fix") && text.contains(state), text)
          val links = note.findAll((n: js.Dynamic) => n.nodeName.asInstanceOf[String] == "A")
            .asInstanceOf[js.Array[js.Dynamic]].toList
          assert(links.size >= 2, "issue and fix must both be links")
          println(s"Upstream example $index: $text")
        }
      }
    }.map { _ => val _ = root.unmount(); () }
  }

  test("style choices shrink the first story's diff and resolution shows local production values") {
    val container   = install(Github())
    val root        = mount(container)(Playground())
    val (_, output) = panes(container)
    val before      = output.value.asInstanceOf[String]
    val _           = find(container, "nesting-option").fire("click")
    settle(250)
      .flatMap { _ =>
        val after  = output.value.asInstanceOf[String]
        val source = ExampleData.showcase.head.input
        assert(Status.changedLines(source, after) < Status.changedLines(source, before))
        val _ = findAll(container, "example")(2).fire("click")
        settle(250)
      }
      .map { _ =>
        assert(output.value.asInstanceOf[String].contains("include"))
        assert(output.value.asInstanceOf[String].contains("${?PORT}"))
        val _        = find(container, "resolved-tab").fire("click")
        val resolved = output.value.asInstanceOf[String]
        assert(resolved.contains("9000"), resolved)
        assert(resolved.contains("production"), resolved)
        assert(!resolved.contains("${?PORT}"), resolved)
        assert(!resolved.contains("__INCLUDE_"), resolved)
        assert(find(container, "resolution-note").textContent.asInstanceOf[String].contains("Includes are not loaded"))
        val _ = find(container, "formatted-tab").fire("click")
        assert(output.value.asInstanceOf[String].contains("include"))
        val _ = root.unmount()
        ()
      }
  }

  test("resolution preserves user placeholder names, explains missing values, and keeps refusals untouched") {
    val container       = install(Github())
    val root            = mount(container)(Playground())
    val (input, output) = panes(container)
    type_(input, "__INCLUDE_0 = user-value\n")
    settle(250)
      .flatMap { _ =>
        val _ = find(container, "resolved-tab").fire("click")
        assert(output.value.asInstanceOf[String].contains("user-value"))
        type_(input, "value = ${MISSING}\n")
        settle(250)
      }
      .flatMap { _ =>
        assert(output.value.asInstanceOf[String].startsWith("Resolution unavailable:"))
        val safety = ExampleData.showcase.last.input
        type_(input, safety)
        settle(250).map { _ =>
          assertEquals(output.value.asInstanceOf[String], safety)
          assert(find(container, "findings").textContent.asInstanceOf[String].contains("service.port"))
          val _ = find(container, "formatted-tab").fire("click")
          assertEquals(output.value.asInstanceOf[String], safety)
          type_(input, "a = 1\n")
        }
      }
      .flatMap(_ => settle(250))
      .map { _ =>
        assertEquals(find(container, "findings").textContent.asInstanceOf[String], "")
        val _ = root.unmount()
        ()
      }
  }

  // --- the contributions section ---------------------------------------------------------------

  test("building the contributions section asks GitHub nothing; mounting it asks once per repository") {
    val github    = Github()
    val container = install(github)
    val section   = ContributionsView()
    assertEquals(github.searched.toList, Nil, "building the section must not start the refresh")
    val _ = mount(container)(section)
    assertEquals(github.searched.size, Library.values.size)
    assertEquals(github.searched.distinct.size, Library.values.size, "one request per repository, no repeats")
    assert(stateLine(container).contains("checking GitHub"), stateLine(container))
    assert(findAll(container, "library").isEmpty, "the list waits for the answer")
  }

  test("a mounted section settles on the answer and lists what the answer brought") {
    val github    = Github()
    val container = install(github)
    val _         = mount(container)(ContributionsView())
    github.answer()
    settle(50).map { _ =>
      assert(stateLine(container).startsWith("State as of"), stateLine(container))
      assert(stateLine(container).contains("refreshed from GitHub"), stateLine(container))
      assertEquals(findAll(container, "library").size, Groups(Contributions.all).size)
      assert(findAll(container, "entry").size >= Contributions.all.size, "every snapshot entry is on the page")
      ()
    }
  }

  test("an answer that arrives after the section is unmounted does not reach it") {
    val github    = Github()
    val container = install(github)
    val root      = mount(container)(ContributionsView())
    val line      = find(container, "state-line")
    val _         = root.unmount()
    github.answer()
    settle(50).map { _ =>
      assertEquals(line.textContent.asInstanceOf[String], "checking GitHub…")
      assertEquals(children(container).size, 0)
      ()
    }
  }

  test("a remount starts the refresh again, and neither mount's subscriptions pile up") {
    val github    = Github()
    val container = install(github)
    val section   = ContributionsView()
    val first     = mount(container)(section)
    assertEquals(github.searched.size, Library.values.size)
    val subscriptions = ReactiveElement.numDynamicSubscriptions(section)
    val _             = first.unmount()
    github.store.clear() // what ten minutes, or a browser that keeps no storage, leaves behind
    val second = mount(container)(section)
    assertEquals(github.searched.size, Library.values.size * 2, "each mount owns its refresh")
    assertEquals(
      ReactiveElement.numDynamicSubscriptions(section),
      subscriptions,
      "the second mount must not leave more subscriptions behind than the first"
    )
    github.answer()
    settle(50).map { _ =>
      assert(stateLine(container).startsWith("State as of"), stateLine(container))
      val _ = second.unmount()
      ()
    }
  }
}
