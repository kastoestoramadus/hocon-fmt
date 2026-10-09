package ww86.hoconfmt.site

class DeployStampSpec extends munit.FunSuite {

  val sha = "0123456789abcdef0123456789abcdef01234567"

  test("a build told what it deployed stamps the UTC time and links the commit") {
    assertEquals(
      DeployStamp.of(Some(sha), Some("2026-10-09 09:31 UTC")),
      DeployStamp.Stamp(
        "deployed 2026-10-09 09:31 UTC · ",
        Some("0123456" -> s"${Repo.url}/commit/$sha")
      ),
      "the stamp shows a short sha, and the link goes to the whole commit on GitHub"
    )
  }

  test("a build told nothing, or only half of it, is a local build with nothing to link") {
    assertEquals(DeployStamp.of(None, None), DeployStamp.local)
    assertEquals(DeployStamp.of(Some(sha), None), DeployStamp.local)
    assertEquals(DeployStamp.of(None, Some("2026-10-09 09:31 UTC")), DeployStamp.local)
    assertEquals(DeployStamp.of(Some("  "), Some(" ")), DeployStamp.local, "blank is as good as missing")
  }
}
