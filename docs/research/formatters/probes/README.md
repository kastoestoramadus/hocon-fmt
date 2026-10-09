# Research probe bytes and observations

Run from the repository root:

```sh
scripts/run-research-probes.sh
# After reviewing changed observations, explicitly update the ledger:
scripts/run-research-probes.sh --record
```

Requires sbt, Java/javac (recorded with Temurin 25.0.4), Python 3 and a POSIX filesystem as an
ordinary user; root does not exercise permission failures. Defaults to 12 parallel JVM **processes**;
`HOCON_RESEARCH_JOBS=4` reduces memory/CPU pressure. Each process gets two available processors,
a 4 MB stack and `-XX:-UsePerfData`: without the last flag parallel short-lived JVMs race on
`/tmp/hsperfdata_<user>` and one prints a locked-file warning into the recorded stdout. Depth
results therefore depend on that budget. Per-process timeout is 45 seconds.
`HOCON_RESEARCH_M2` overrides the task-private `../m2`; `HOCON_RESEARCH_RUNTIME` defaults to `/tmp`.
The shell builds released-sconfig `cliJVM`, not the playground fork. Fork fetch is run as required
by the repository; it may publish its pinned Scala.js artifact to Ivy, as its own script documents.

`manifest.json` lists complete physical cases, source URL/licence and original research references.
`inventory.json` preserves each proposed table row/prose snippet and maps duplicates or abstract
setups to concrete cases. A range/editor API operation with no HOCON input is explicitly marked
not runnable. A proposed external corpus **mechanism** is represented by the complete local examples
and goldens; no claim is made to re-run the historic 1,650 external files. References marked
`case-*` are full-context analogues assembled from prose, not copied upstream test code.
The shared catalogue contains P1–P6/P8–P21 (no P7 in the input research); all supplied IDs are covered.

The sibling `.source` file carries a **header comment with URL and licence**. It is kept outside the
actual byte input on purpose: injecting a header changes empty/comment-only probes, BOM position,
comment attachment, line numbers, EOF and exact golden bytes. URLs identify the upstream source of
the idea; newly authored HOCON analogues are project material, not copied implementation. Large
100 KB/300 KB values and deep nesting are generated analogues and deliberate exceptions to small files.
Original Markdown uses `\n` as control notation; where a table describes multiple alternatives they
are separate files. Full-context prose variants complement literal snippets and their original
expectations remain in the inventory. Profile expectations are hypotheses, not authoritative CLI policy.

`probes.tsv` records every separator's expectation, actual exits and owner. `actual.json` contains
actual stdout/stderr for initial check, write, second write, check-after and stdin; `actual_hex` is
exact post-write bytes (including refused invalid UTF-8), and `bare_sconfig_hex` is the **unmasked**
bare-library rendering. Its resolver uses `noSystem`, with the deterministic `f.conf` fixture from
`run.py`. A root string-literal oracle separately compares triple-quoted payload bytes. Unresolved
or invalid inputs are explicitly labelled by the oracle; they are not counted as semantic checks.
An ordinary bare renderer expands includes; it is not expected to preserve source directives.

Temporary directory and dependency cache paths are normalized, including the source path inside
sconfig's unresolved-merge banner in `bare_sconfig_hex` (the bytes are normalized before hex
encoding, since a hex string has no other place for it); runtime warnings and formatter
messages are retained. No production source is changed by the runner. Filesystem setups and
observations are in `filesystem-actual.json`, including style lookup, ignores, mixed batches,
read-only/symlink/hard-link behavior and actual process kills when staging or truncation is observed.
Kill replays compare contract booleans rather than the timing-dependent remaining byte count;
the exact recorded count stays as evidence. These probes deliberately demonstrate the documented
fallback limitation, not an unconditional atomicity promise.

A **green replay means observations match**, including known bad behavior; it does not mean the
formatter is bug-free. Promote selected cases into actual regression suites only after adding
invariant assertions, a deliberate failing mutation and a cost tier. `--only file.conf,other.conf`
is an investigation/update aid; a full release replay omits it.
