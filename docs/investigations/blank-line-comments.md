# Comments above a blank line

## The gap

sconfig attaches a comment to the field below it. A comment that a blank line separates from the
next field, one after the last field of an object or file, and every comment of a comment-only
file are dropped on parse ([limitations](../limitations.md)). The formatter notices and refuses
(`Refusal.LostComment`), so nothing is lost, but the commonest wild shape, a licence header
followed by a blank line, is never formatted.

## Impact

On the 1,650-file GitHub corpus (2026-10-08), 818 files are refused for a lost comment, the
largest refusal class. Blank lines are a separate loss: sconfig keeps no spacing, and the
checks accept it.

## Two routes, used together

1. **sconfig keeps the comments, opt-in.** [ekrich/sconfig#646](https://github.com/ekrich/sconfig/issues/646),
   fix in draft [#647](https://github.com/ekrich/sconfig/pull/647): `setKeepDetachedComments(true)`
   attaches such a block to the field below it, in order. Off by default because lightbend/config
   drops them on purpose. Tested there on JVM, Scala.js and Native; not released.
2. **The formatter masks what is left**, as it does includes: a block no field follows becomes a
   placeholder field before the parse and is put back after the render
   (`core/site-shared/.../CommentCarrier.scala`).

The playground uses both, on a sconfig fork ([site](../site.md#running-ahead-of-the-release)); the
command line and the plugins use neither and refuse as before.

## Corpus, 1,650 files

Measured with a throwaway Node harness over the linked `coreJS` (released sconfig 1.12.4) and
`coreSite` (the fork, pinned sha efb66e0131), `Verdict.of(text, "corpus")` per file. The corpus is
untrusted downloaded data; it was read, never executed.

| verdict | released sconfig (CLI) | fork, no option, no masking | fork + option | fork + option + masking (the page) |
|---|---:|---:|---:|---:|
| already-formatted / needs-formatting | 28 / 298 | 28 / 662 | 28 / 915 | 28 / 1,384 |
| refused: lost-comment | 818 | 819 | 565 | 85 |
| refused: unstable-output | 200 | 0 | 0 | 8 |
| refused: broken-output | 164 | 0 | 0 | 0 |
| refused: moved-include | 0 | 0 | 1 | 4 |
| refused: not-hocon | 142 | 141 | 141 | 141 |

Per file, released against the page: 1,086 refused files format, nothing that formatted is
refused (0 regressions). Of the 1,086, 364 come from the fork's base (unstable and broken outputs
that sconfig main renders correctly), 253 from the option and 469 from the masking. Eight
lost-comment files move to unstable-output and four to moved-include: refusals changing kind, not
files lost.

The 85 files still refused for a lost comment have a block before the `]` or `)` of an array, a
braced root's header, or are merge-heavy files whose comments the merges move (three of them hold
most of the missing comments). The measure that rejected the probe's wider masking: with the
option on, also masking the blocks before fields and keeping blank lines gave about the same
lost-comment count but hundreds of unstable-output refusals, because those placeholders are what
destabilise the render. Hence the masking is only the residual.

## Checks on the newly formatted files (the probe's run, before the fork)

Idempotent: all. Resolved JSON of the masked input equals the output's everywhere; the one
mismatch is placeholder renumbering in the comparison, and the baseline flags it too. Comments:
the multisets are equal everywhere. In order: 47 of 260 files showed a comment moved between merged
keys, which is sconfig's own attachment, not the masking.

## Limits

- **Comments before the end of an array** stay refused. A field cannot stand there, so the masking
  never reaches them.
- **A comment on a `+=` field renders twice**, found by the probe and an upstream defect: sconfig
  copies the comment onto both halves of the desugared form.

## Why blank-line keeping was rejected

Keeping every blank-line run doubled the number of formatted files in the probe but froze sconfig's
synthetic `${?VAR}` merge banner
([ekrich/sconfig#600](https://github.com/ekrich/sconfig/issues/600)) into the file: a blank
placeholder lets the banner attach and stop growing, so 351 unstable-output refusals turned into
stable output that carries a banner the author never wrote. That is a workaround for an upstream
defect inside the formatter, which AGENTS.md forbids.

## When sconfig ships the option

What becomes removable: the fork, the script, `coreSite` and the seam; the masking of blocks no
field follows is the open question, since the option does not cover it (compare with the table
above). The signal is `KeepDetachedCommentsGuardSpec`; the steps are in
[site](../site.md#returning-to-upstream-sconfig).

## Decision

The playground runs on the fork with the residual masking. The command line and the plugins stay on
released sconfig and refuse as today.
