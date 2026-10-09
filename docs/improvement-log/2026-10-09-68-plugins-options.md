# 2026-10-09 — [#68](https://github.com/kastoestoramadus/hocon-fmt/pull/68) — Build plugins use repository options and report dead duplicates

**Change:** A repository with `separator = ":"` in `.hocon-fmt.conf` used to format differently
through the CLI and build plugins. sbt, Gradle, Maven and Mill now discover the nearest config,
stop at `.git` (including worktree files), and apply only explicitly supplied settings over it.
They warn about original dead duplicate definitions and fail either command only when the effective
`fail-on-duplicates` setting requests it. The Java API exposes renderer options, findings, report
failures and inspections as JSpecify-marked JDK records; no new public signature exposes Scala types.
The pure lookup decision and `StyleOverrides` moved to core. Filesystem probes, walking parent paths,
strict UTF-8 reads and writes remain at the CLI, Java API and Mill edges; core still depends only on
sconfig. The Java integrations retain the identity-preserving writer.

**Validation:** Tests were committed before implementation. TestKit initially reported `1 test
completed, 1 failed`: colon output expected, default equals output produced. The API regression tests
initially failed to compile with `13 errors` for the missing API. A later config-error regression
reported `4 tests completed, 1 failed`, then passed after naming the unreadable config. The shared
fixture and `scripts/plugin-parity.sh` compare CLI bytes with scripted, TestKit, invoker and real Mill
harnesses. Explicit booleans include false overrides; Gradle checks config input invalidation and
configuration-cache reuse. A symlink regression first failed at `assertAliasStyle`; sbt now
keeps the selected path for config lookup while deduplicating sources by canonical path. Refusal
and identity-preserving write regressions remain covered. A red TestKit check (`1 test completed,
1 failed`) also pins that duplicate policy cannot suppress other unformatted-file diagnostics. Gradle passed 15 tests; Maven invoker
reported `Passed: 14, Failed: 0`; Mill passed 9 unit tests and 2 integration tests on each host;
Java/Kotlin passed 34 tests. The final full sbt run passed 2,042 tests and both scalafmt checks; all 9 scripted projects passed. The parity script reported `Plugin parity: CLI + sbt + Gradle + Maven
+ Mill passed with byte-identical output.`

**Look at again before a release:** Mill boolean task arguments take explicit `true`/`false` values.
Gradle tracks all ancestor config paths, so changing an outer config can invalidate a check even when
`.git` keeps that config from affecting formatting. The lookup deliberately retains edge-specific
filesystem effects; its shared core decision has no I/O dependency. Nothing is released.

Generated with gpt-6.1-sol/m through Codex
