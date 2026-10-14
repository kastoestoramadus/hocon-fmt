# 2026-10-14 — #129 — choose release targets explicitly

**Change:** Q26 replaces decision 61: the owner chooses channels, and a missing selected credential fails
before any upload rather than silently dropping the Gradle submission. Dispatch checkboxes and
`RELEASE_TARGETS` on tags feed one canonical selection in the secret-free version job. The shared
preflight checks credentials and core/Java API visibility on Central for a plugin-only upload.
The sbt commands split the JVM artifacts from the sbt plugin; unselected channels need no secrets.

**Look at again before a release:** run the intended selection as a dry run with real signing
secrets, then verify Portal credentials and Central visibility for plugin-only selections. Mill,
npm and PyPI remain selectable but fail preflight until their repository prerequisites are enabled.
Native publishes binaries and the existing web script. Shell stubs and actionlint do not exercise
GitHub expressions, environment approvals, real signatures or registry acceptance. Core deployments
still require the owner's Central Publish click before consumers can resolve the plugins.
