package eu.ww86.hoconfmt.mill

import scala.concurrent.duration.*

import mill.testkit.IntegrationTester

/** A real Mill, loading the plugin the way a user's build does, from `//| mvnDeps`. */
class PluginIntegrationSpec extends munit.FunSuite {

  // On a fresh machine the first command downloads the Mill under test and the JVM it runs on.
  override val munitTimeout: Duration = 10.minutes

  val build = s"""//| mvnDeps:
                 |//| - ${sys.env("PLUGIN_UNDER_TEST")}
                 |package build
                 |
                 |import mill.*, javalib.*
                 |import eu.ww86.hoconfmt.mill.HoconFormatterModule
                 |
                 |object app extends JavaModule, HoconFormatterModule {
                 |  object test extends JavaTests, TestModule.Junit4, HoconFormatterModule
                 |}
                 |""".stripMargin

  test("repository config, explicit overrides and duplicate failures match the CLI fixture") {
    val tester = IntegrationTester(
      daemonMode = false,
      workspaceSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "options-project",
      millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
    )
    try {
      os.write(tester.workspacePath / "build.mill", build)
      val file = tester.workspacePath / "app" / "resources" / "app.conf"
      val input = os.read(tester.workspacePath / "input.conf")
      val format = tester.eval("app.hoconFormat")
      assert(format.isSuccess, format.debugString)
      assert(format.err.contains("defined again"), format.debugString)
      assertEquals(os.read(file), os.read(tester.workspacePath / "expected.conf"))
      os.write.over(file, input)
      val failure = tester.eval(Seq("app.hoconFormat", "--fail-on-duplicates", "true"))
      assert(!failure.isSuccess, failure.debugString)
      assert(failure.err.contains("duplicate definitions"), failure.debugString)
      os.write.over(file, input)
      val overrideStyle = tester.eval(Seq("app.hoconFormat", "--separator", "=", "--double-indent", "true", "--simplify-nested-objects", "false"))
      assert(overrideStyle.isSuccess, overrideStyle.debugString)
      assertEquals(os.read(file), "a = 1\na = 2\nb { c = 3 }\n")
    } finally tester.close()
  }

  test("__.hoconFormatCheck names the unformatted files of every module, __.hoconFormat fixes them") {
    val tester = IntegrationTester(
      daemonMode = false,
      workspaceSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "project",
      millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
    )
    try {
      os.write(tester.workspacePath / "build.mill", build)
      val application = tester.workspacePath / "app" / "resources" / "application.conf"
      val nginx       = tester.workspacePath / "app" / "resources" / "nginx.conf"
      val testConf    = tester.workspacePath / "app" / "test" / "resources" / "test.conf"
      val nginxBefore = os.read(nginx)

      val check = tester.eval("__.hoconFormatCheck")
      assert(!check.isSuccess, check.debugString)
      assert(check.err.contains("Not formatted: app/resources/application.conf"), check.debugString)
      assert(check.err.contains("Not formatted: app/test/resources/test.conf"), check.debugString)
      assert(check.err.contains("Leaving app/resources/nginx.conf unchanged"), check.debugString)

      val format = tester.eval("__.hoconFormat")
      assert(format.isSuccess, format.debugString)
      assertEquals(os.read(application), "include \"local.conf\"\napp {\n  name = \"svc\"\n  port = 8080\n}\n")
      assertEquals(os.read(testConf), "timeout = 5s\n")
      assertEquals(os.read(nginx), nginxBefore)

      val recheck = tester.eval("__.hoconFormatCheck")
      assert(recheck.isSuccess, recheck.debugString)
      Seq("app.hoconFormat", "app.hoconFormatCheck").foreach { command =>
        val strict = tester.eval(Seq(command, "--fail-on-refusal", "true"))
        assert(!strict.isSuccess, strict.debugString)
        assert(strict.err.contains("HOCON formatting refusals found."), strict.debugString)
        assertEquals(os.read(nginx), nginxBefore)
      }
      os.write(tester.workspacePath / ".hocon-fmt.conf", "fail-on-refusal = true\nfail-on-duplicates = true\n")
      val cleared = tester.eval(Seq("app.hoconFormatCheck", "--fail-on-refusal", "false"))
      assert(cleared.isSuccess, cleared.debugString)
      val independent = tester.eval(Seq("app.hoconFormatCheck", "--fail-on-duplicates", "false"))
      assert(!independent.isSuccess, independent.debugString)
      assert(independent.err.contains("HOCON formatting refusals found."), independent.debugString)
      assertEquals(os.read(nginx), nginxBefore)
    } finally tester.close()
  }
}
