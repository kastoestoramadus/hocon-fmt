# Playground

A page where anyone can paste HOCON and see what the formatter makes of it, with nothing
installed and nothing sent to a server. The page is part of this repository now: the `site`
module renders it with Laminar and calls the core directly. See [site](site.md) for how it is
built, tested, and verified. This file keeps the spec the page answers to.

## The engine

There is no engine/script boundary any more: the page depends on `coreJS`, so the playground
calls `Verdict.of(text)` itself and gets the same three outcomes the [web](../web) script exposes
as JSON:

- `AlreadyFormatted` — the status line reads "Already formatted".
- `NeedsFormatting(formatted)` — "Formatted", with how many lines changed.
- `Refused(refusal)` — "Left unchanged", the reason, and one sentence per refusal kind.

The `web` module and its `HoconFormatter.format` global stay, for pages that only want the
script; every GitHub release carries `hocon-fmt.js`.

## The page

1. **Intro**: what HOCON is and what a formatter adds, in a few sentences; the promise that a
   file is refused rather than corrupted, and that nothing pasted leaves the browser.
2. **Two panes**, input and output, formatting as you type with a debounce of 150 ms. Under the
   output, one status line:
   - formatted: "Formatted", with how many lines changed — input and output compared line by
     line from the top, no diff machinery;
   - already formatted: "Already formatted";
   - refused: "Left unchanged", the reason from `Refusal.reason`, one sentence naming what the
     refusal means, and a link to the [limitations](limitations.md) section that reproduces it.
3. **Examples**, one button each, each showing one property:
   - a messy config — indentation, spacing, the nested object;
   - includes that survive (the README's example);
   - comments kept, including one at the end of a line, which moves above;
   - not HOCON at all (an nginx config) — refused as `notHocon`;
   - a known sconfig defect (`a : [1]` then `a += 2`) — refused as `brokenOutput`, which is the
     bridge to the contributions below it.
4. **The work upstream**: the author's pull requests against sconfig and lightbend/config, the
   defect table, and the state line ("as of …"), from the shipped snapshot refreshed live where
   GitHub answers; see [site](site.md#the-snapshot-and-the-live-refresh).
5. **Footer**: the version from BuildInfo, the source at the matching tag (the repository root
   while the version is a SNAPSHOT), GPL-3.0, and a link to [ww86.eu](https://ww86.eu).

The ww86.eu page rules apply: relative links within the site, English copy, `lang` and a meta
description, and it must work from `file://`.

## Later

- **Share by link**: the input in the URL fragment, which never reaches a server, so a refused
  input can be passed on as one link.
- **A diff** of input and output instead of the output alone.
- **"Report this refusal"**: a prefilled GitHub issue, only on an explicit click, since pasted
  configs can be private.
