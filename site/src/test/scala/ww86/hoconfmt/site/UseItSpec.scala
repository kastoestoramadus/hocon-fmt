package ww86.hoconfmt.site

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js

class UseItSpec extends munit.FunSuite {
  def installed(hash: String = ""): js.Dynamic = {
    val document = FakeDom.document()
    val global   = js.Dynamic.global.globalThis
    global.updateDynamic("document")(document)
    global.updateDynamic("window")(
      js.Dynamic.literal(
        location = js.Dynamic.literal(hash = hash),
        addEventListener = js.Any.fromFunction2((_: String, _: js.Any) => ()),
        removeEventListener = js.Any.fromFunction2((_: String, _: js.Any) => ())
      )
    )
    val container = document.createElement("div")
    val _         = document.selectDynamic("body").appendChild(container)
    container
  }
  def nodes(container: js.Dynamic, tag: String): List[js.Dynamic] =
    container
      .findAll((n: js.Dynamic) => n.tagName.asInstanceOf[String] == tag)
      .asInstanceOf[js.Array[js.Dynamic]]
      .toList
  def named(container: js.Dynamic, text: String): js.Dynamic =
    nodes(container, "BUTTON").find(_.textContent.asInstanceOf[String] == text).getOrElse(fail(s"missing $text"))
  override def afterEach(context: AfterEach): Unit = {
    js.Dynamic.global.globalThis.updateDynamic("window")(js.undefined)
    js.Dynamic.global.globalThis.updateDynamic("document")(js.undefined)
    super.afterEach(context)
  }
  test("integration pills are native buttons; only the chosen panel is mounted and its hash is kept") {
    val container = installed()
    val root      = render(container.asInstanceOf[dom.Element], UseIt())
    val expected  = List(
      "CLI native",
      "CLI npm",
      "CLI JVM",
      "pre-commit",
      "sbt",
      "Gradle",
      "Maven",
      "Mill",
      "Java / Kotlin",
      "Core",
      "cats-effect",
      "ZIO",
      "Scala.js"
    )
    expected.foreach(label =>
      assertEquals(
        named(container, label).getAttribute("aria-pressed").asInstanceOf[String],
        if (label == "CLI native") "true" else "false"
      )
    )
    val _ = named(container, "sbt").fire("click")
    assertEquals(named(container, "sbt").getAttribute("aria-pressed").asInstanceOf[String], "true")
    assertEquals(js.Dynamic.global.window.location.hash.asInstanceOf[String], "#use-it-sbt")
    assertEquals(nodes(container, "PRE").size, 1)
    val _ = root.unmount()
  }
  test("hash restores an API; code is editable and remote run is disabled before release") {
    val container = installed("#use-it-core")
    val root      = render(container.asInstanceOf[dom.Element], UseIt())
    val box       = nodes(container, "TEXTAREA").head
    assert(box.value.asInstanceOf[String].contains("HoconFormatter"))
    assertEquals(named(container, "Run in Scastie").disabled.asInstanceOf[Boolean], true)
    assert(container.textContent.asInstanceOf[String].contains("works after the first release"))
    box.updateDynamic("value")("//> using dep evil:library:1")
    val _ = box.fire("input")
    assert(container.textContent.asInstanceOf[String].contains("Dependencies are fixed"))
    val _ = root.unmount()
  }
  test("every API rejects dependency and launcher directives before hand-off") {
    val container = installed("#use-it-zio")
    val root      = render(container.asInstanceOf[dom.Element], UseIt())
    val box       = nodes(container, "TEXTAREA").head
    List(
      "//> using dep x",
      "//> using scala 3",
      "import $ivy.`x:y:1`",
      "libraryDependencies += x",
      "scala-cli run main.scala"
    ).foreach { text =>
      box.updateDynamic("value")(text)
      val _ = box.fire("input")
      assert(container.textContent.asInstanceOf[String].contains("Dependencies are fixed"), text)
    }
    box.updateDynamic("value")("println(42)")
    val _ = box.fire("input")
    assert(!container.textContent.asInstanceOf[String].contains("Dependencies are fixed"))
    val _ = root.unmount()
  }
  test("the pre-commit tab carries the wave-2 note, in the docs' words") {
    val container = installed("#use-it-pre-commit")
    val root      = render(container.asInstanceOf[dom.Element], UseIt())
    val note      = "The hooks ship in wave 2: they pin PyPI and npm versions published only then."
    assert(container.textContent.asInstanceOf[String].contains(note))
    val usage = js.Dynamic.global.require("fs").readFileSync("docs/usage.md", "utf8").asInstanceOf[String]
    assert(usage.contains(note))
    val _ = root.unmount()
  }

  test("displayed install commands are verbatim usage documentation") {
    val fs    = js.Dynamic.global.require("fs")
    val usage = fs.readFileSync("docs/usage.md", "utf8").asInstanceOf[String]
    assertEquals(
      UseItExamples.commands.keySet,
      Set("native", "npm", "jvm", "pre-commit", "sbt", "gradle", "maven", "mill")
    )
    UseItExamples.commands.values.foreach(command => assert(usage.contains(command), command))
    UseItExamples.actions.values.flatten.foreach(command => assert(usage.contains(command), command))
  }

  test("Scastie receives arbitrary edited code and only the chosen build") {
    val core    = UseIt.integrations.find(_.id == "core").get
    val code    = "object Main { def main(args: Array[String]): Unit = println(42) }"
    val url     = ScalaShowcase.scastieUrl(core, code).toOption.get
    val decoded = js.URIUtils.decodeURIComponent(url.drop(url.indexOf("inputs=") + 7))
    val inputs  = js.JSON.parse(decoded).asInstanceOf[js.Dynamic].SbtInputs
    assertEquals(inputs.code.asInstanceOf[String], code)
    assertEquals(inputs.sbtConfigExtra.asInstanceOf[String], ScalaShowcase.buildConfig(core))
    assertEquals(inputs.target.Scala3.scalaVersion.asInstanceOf[String], "3.8.2")
    assert(ScalaShowcase.scastieUrl(core, "import $ivy.`x:y:1`").isLeft)
  }

  test("API edits survive selecting another integration") {
    val container = installed("#use-it-core")
    val root      = render(container.asInstanceOf[dom.Element], UseIt())
    val box       = nodes(container, "TEXTAREA").head
    box.updateDynamic("value")("println(42)")
    val _ = box.fire("input")
    val _ = named(container, "ZIO").fire("click")
    val _ = named(container, "Core").fire("click")
    assertEquals(nodes(container, "TEXTAREA").head.value.asInstanceOf[String], "println(42)")
    val _ = root.unmount()
  }

}
