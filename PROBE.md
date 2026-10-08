# Probe: comment masking

A kept, deliberate probe, not junk. Do not merge it as it is.

## What it does

sconfig drops a comment that no field follows directly (a blank line after it, the end of an object
or file, a comment-only file). After `IncludeMasking`, `ProbeMasking` turns each such comment block
into a placeholder field `__COMMENT_n : "__COMMENT_n"` with a guard, parses and renders once, and
puts the comment back. The mode `blanks` also keeps every blank-line run as one blank line. One
parse per pass, composed with include masking. `IncludeOrder` ignores the new keys.

- `core/shared/.../ProbeMasking.scala`, the masking (295 lines, RE2 and ES2015 safe regexes)
- `core/jvm/.../ProbeRun.scala`, a JVM-only corpus harness
- three lines of wiring in `HoconFormatter` and `IncludeOrder`

## Numbers (1,650 corpus files, rerun on this branch)

| verdict | off | comments | blanks |
|---|---:|---:|---:|
| already-formatted / needs-formatting | 28 / 298 | 28 / 435 | 33 / 781 |
| refused: lost-comment | 818 | 84 | 84 |
| refused: unstable / broken / not-hocon | 200 / 164 / 142 | 732 / 228 / 142 | 382 / 227 / 142 |

`comments` recovers 137 files, `blanks` 489, with no regressions. The rest is refusal churn among
files that were already refused. The 84 files still refused for lost comments have a comment above
a blank line inside an array, where no field can stand. Details:
[docs/investigations/blank-line-comments.md](docs/investigations/blank-line-comments.md).

## Decision

The playground will use this together with the sconfig fork (plan in progress). The CLI and the
plugins will not: they stay on released sconfig and keep refusing, as today.

With the probe on, `sbt coreJVM/test` fails exactly four tests that pin today's refusal
(`ExamplesSpec` `detached-header-comment` and `trailing-comment-in-object`, `HoconSpecCoverageSpec`
two `commentAboveBlankLine` cases). They are left as they are on purpose.

## Rerun

```bash
sbt -J-Xmx5g coreJVM/test
# files.txt: absolute paths, one per line; mode is off, comments or blanks
sbt "coreJVM/Test/runMain ww86.hocon_fmt.ProbeRun corpus comments files.txt out.jsonl -"
```

The corpus is untrusted downloaded data: read it, never execute it, and read the output with
`python3 -I`.

## Upstream

- [ekrich/sconfig#646](https://github.com/ekrich/sconfig/issues/646), the issue
- [ekrich/sconfig#647](https://github.com/ekrich/sconfig/pull/647), draft fix: `setKeepDetachedComments`
- [ekrich/sconfig#643](https://github.com/ekrich/sconfig/issues/643), the earlier issue
