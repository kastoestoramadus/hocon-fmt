# Comments above a blank line

## The gap

sconfig attaches a comment to the field below it. A comment that a blank line separates from the
next field, one after the last field of an object or file, and every comment of a comment-only
file are dropped on parse ([limitations](../limitations.md)). The formatter notices and refuses
(`Refusal.LostComment`), so nothing is lost, but the commonest wild shape, a licence header
followed by a blank line, is never formatted.

## Impact

On the 1,650-file GitHub corpus (2026-10-08), 818 files are refused for a lost comment, the
largest class after none. Blank lines are a separate loss: sconfig keeps no spacing, and the
checks accept it.

## Two routes

1. **sconfig keeps the comments, opt-in.** [ekrich/sconfig#646](https://github.com/ekrich/sconfig/issues/646),
   fix in draft [#647](https://github.com/ekrich/sconfig/pull/647): `setKeepDetachedComments(true)`
   attaches such a block to the field below it, in order. Off by default because lightbend/config
   drops them on purpose. Tested there on JVM, Scala.js and Native; not released.
2. **The formatter masks them**, as it does includes: each dropped comment block becomes a
   placeholder field before the parse and is put back after the render. The branch
   `probe/comment-masking` holds it (`PROBE.md` there says how to rerun).

Corpus, 1,650 files, `blanks` also keeps each blank-line run as one blank line:

| | before | comments | blanks |
|---|---:|---:|---:|
| already-formatted / needs-formatting | 28 / 298 | 28 / 435 | 33 / 781 |
| refused: lost-comment | 818 | 84 | 84 |
| refused: unstable / broken / not-hocon | 200 / 164 / 142 | 732 / 228 / 142 | 382 / 227 / 142 |

`comments` recovers 137 files and `blanks` 489, with no regressions. The other verdict changes
are refusals changing kind among files that were refused already: a defect that `lost-comment`
used to hide (532 files go from lost-comment to unstable-output) is now reported as itself.

## Checks on the newly formatted files

Idempotent: all. Resolved JSON of the masked input equals the output's everywhere; the one
mismatch (file 1326) is placeholder renumbering in the comparison, and the baseline flags it too.
Comments: the multisets are equal everywhere. In order: 47 of 260 files show a comment moved
between merged keys, which is sconfig's own attachment, not the masking.

## Limits

- **Comments above a blank line inside an array** stay refused, 84 files. A field cannot stand
  there, so the masking never reaches them.
- **A comment on a `+=` field renders twice**, found by the probe and an upstream defect: sconfig
  copies the comment onto both halves of the desugared form.

## Why blank-line keeping was rejected

`blanks` doubles the win but freezes sconfig's synthetic `${?VAR}` merge banner
([ekrich/sconfig#600](https://github.com/ekrich/sconfig/issues/600)) into the file: a blank
placeholder lets the banner attach and stop growing, so 351 unstable-output refusals turn into
stable output that carries a banner the author never wrote. That is a workaround for an upstream
defect inside the formatter, which AGENTS.md forbids.

## When sconfig ships the option

This is reasoned from the fix's tests and the probe's code, not measured. The option covers the
blocks before a field and before an array element. It does not cover a block at the end of an
object, a comment-only file or the header of a braced root, so the masking would shrink to those
three, not disappear. What becomes removable is the masking of blocks before fields; the
refusal tests that turn green are the signal: `ExamplesSpec` `detached-header-comment` and the two
`commentAboveBlankLine` cases in `HoconSpecCoverageSpec`. `SconfigDefectsSpec` should get a
`library:` test for the opt-in, so a release that carries it flips it.

## Decision

The playground will use the sconfig fork together with the masking. The command line and the
plugins stay on released sconfig and refuse as today. The probe is kept, not merged as is; the
PR that carries it is meant to grow into the playground variant.
