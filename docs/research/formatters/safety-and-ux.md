# Formatters: how they prove they are safe, and what users demand of them

Family: cross-cutting. Four profiles — **Black**, **Prettier**, **rustfmt**, **clang-format** — because those
four carry the safety techniques the family is about (AST equivalence checks, format-twice suites,
corpus testing, fuzzing, differential testing), and a closing section on the shared UX sources
(EditorConfig, pre-commit) plus the two tools that add what the four do not: **gofmt** and **scalafmt**.

Method: issue titles, states and dates below come from the GitHub GraphQL search API, read on
2026-10-09; a quoted title is verbatim, the state is as of that read, and the date is the issue's
creation date (the API returns creation, not closing, dates). Doc claims come from the raw files and
pages linked inline. Nothing is from memory; a claim that could not be read is marked *unverified*.
Two threads run through all of it: **how a formatter notices it is wrong**, and **what users insist on
once they trust it** (check mode, exit codes, ignores, ranges, version pinning).

## Black (Python)

**Tool**: Black, Python, [github.com/psf/black](https://github.com/psf/black), betas from 2018, first non-beta
22.1.0 (CHANGES.md: "At long last, _Black_ is no longer a beta product!"); the style is opinionated on
purpose and changes only once a calendar year.

**Design choices that matter to us**
- Configurability is "purposefully limited" ([usage index](https://raw.githubusercontent.com/psf/black/main/docs/usage_and_configuration/index.md)); a stability policy promises a release will not change the stable style within its calendar year, except for bugs, and the first release of a year may ([stability policy](https://raw.githubusercontent.com/psf/black/main/docs/the_black_code_style/index.md)).
- `--check` exits **0** nothing would change, **1** some files would be reformatted, **123** an internal error; `--diff` prints instead of writing; with `--quiet` only the code comes back ([the basics](https://raw.githubusercontent.com/psf/black/main/docs/usage_and_configuration/the_basics.md)).
- `--safe` (the default) compares the AST before and after and refuses when they diverge, with three documented exceptions: docstring whitespace, `del` parentheses, comment movement (comments are AST as of Python 3.8); `--fast` turns the check off ([AST section](https://raw.githubusercontent.com/psf/black/main/docs/the_black_code_style/current_style.md)).
- Idempotence is a property test, not only a promise: [scripts/fuzz.py](https://raw.githubusercontent.com/psf/black/main/scripts/fuzz.py) generates syntactically valid Python with Hypothesis/Hypothesmith and asserts formatting is idempotent across randomly varied `FileMode`s; the AST comparison is a stringified-AST walk ([src/black/parsing.py](https://raw.githubusercontent.com/psf/black/main/src/black/parsing.py)).
- Comments are the acknowledged hard part; `# fmt: off/on/skip` are the escape hatch, and inline comments may be reflowed (same AST page).
- `--line-ranges` is best-effort only: the docs say it "may still format lines outside of the ranges", cite #4052 for extra formatting, and note it disables the safety check ([the basics](https://raw.githubusercontent.com/psf/black/main/docs/usage_and_configuration/the_basics.md)).
- Config discovery stops at the *first* `pyproject.toml` upward even without a `[tool.black]` section ([#2863](https://github.com/psf/black/issues/2863)); `.gitignore` is honoured for exclusions unless `--exclude` is set ([file collection](https://raw.githubusercontent.com/psf/black/main/docs/usage_and_configuration/file_collection_and_discovery.md)); there is no `.editorconfig` support ([#4487](https://github.com/psf/black/issues/4487)).
- A per-user cache keyed by version/line-length/file-mode is on by default, with `--no-cache`, `--cache-dir` and `BLACK_CACHE_DIR` ([file collection](https://raw.githubusercontent.com/psf/black/main/docs/usage_and_configuration/file_collection_and_discovery.md)).
- `--required-version` (major only accepted) pins behaviour across a team; stdin is `-` plus `--stdin-filename` so `--force-exclude` still applies ([the basics](https://raw.githubusercontent.com/psf/black/main/docs/usage_and_configuration/the_basics.md)).

**Bug classes**
- **idempotence** — "produced different code on the second pass": the 80-comment umbrella [#1629](https://github.com/psf/black/issues/1629) "…different code on the second pass of the formatter" CLOSED 2020-08-26, [#853](https://github.com/psf/black/issues/853) "…comments and parentheses" CLOSED 2019-05-16, [#2518](https://github.com/psf/black/issues/2518) "Non idempotent input" CLOSED 2021-10-03, [#2754](https://github.com/psf/black/issues/2754) "not idempotent with comment over line length" CLOSED 2022-01-10, [#5001](https://github.com/psf/black/issues/5001) "@overload … two distinct stable formatting states" CLOSED 2026-02-22.
- **comment-loss** — [#3815](https://github.com/psf/black/issues/3815) "Comment is removed inside two pairs of parentheses" CLOSED 2023-07-26, [#2339](https://github.com/psf/black/issues/2339) "fmt: skip removes a comment-only line before it" CLOSED 2021-06-17.
- **comment-movement** — [#1355](https://github.com/psf/black/issues/1355) "Black moves comments to wrong place" CLOSED 2020-04-22, [#3924](https://github.com/psf/black/issues/3924) "Preview style unnecessarily moves comment" CLOSED 2023-10-06, [#195](https://github.com/psf/black/issues/195) "flake8 #noqa comments get moved a place where they're ignored" CLOSED 2018-05-08, [#379](https://github.com/psf/black/issues/379) "Reflows of inline comments can cause misalignment" OPEN 2018-06-26.
- **semantic-change** — [#2150](https://github.com/psf/black/issues/2150) "Latest `black` changes ASTs" CLOSED 2021-04-26, [#4683](https://github.com/psf/black/issues/4683) "black hook unexpectedly removes exception alias during pre-commit" CLOSED 2025-06-04, [#5099](https://github.com/psf/black/issues/5099) "…produced invalid code: multiple exception types must be parenthesized" CLOSED 2026-04-14.
- **parse-error-handling** — [#4329](https://github.com/psf/black/issues/4329) "cannot parse previously parseable file in 24.4.1" CLOSED 2024-04-24, [#1012](https://github.com/psf/black/issues/1012) "fails to tokenise files ending with a backslash" CLOSED 2019-09-10.
- **whitespace-eol-encoding** — [#5486](https://github.com/psf/black/issues/5486) "Removing leading blank lines can move a coding cookie onto line 1 and change how the file is decoded" CLOSED 2026-10-05, [#1537](https://github.com/psf/black/issues/1537) "crashes when … `get_gitignore` tries to use gbk encoding …" CLOSED 2020-07-07.
- **line-width-layout** — [#1713](https://github.com/psf/black/issues/1713) "Line too long: Comments" CLOSED 2020-09-16, [#1802](https://github.com/psf/black/issues/1802) "Black Fails to Format Single String Longer Than Line Length Limit" CLOSED 2020-11-02, [#2156](https://github.com/psf/black/issues/2156) "Use optional parentheses more often" OPEN 2021-04-27.
- **check-exit-codes / partial-range** — [#4033](https://github.com/psf/black/issues/4033) "`--line-ranges` bad behavior and internal errors" CLOSED 2023-11-08, [#4430](https://github.com/psf/black/issues/4430) "`--line-ranges` formats lines outside of range" CLOSED 2024-08-06, [#4264](https://github.com/psf/black/issues/4264) "formats entire file when ranges are at EOF" CLOSED 2024-03-04, [#5474](https://github.com/psf/black/issues/5474) "Check passes on unformatted file after running with line ranges" CLOSED 2026-10-03.
- **config-ignore** — [#2863](https://github.com/psf/black/issues/2863) (above), [#3040](https://github.com/psf/black/issues/3040) "black does not exclude files in git submodules" OPEN 2022-04-28, [#395](https://github.com/psf/black/issues/395) "Pre-commit-hook ignores exclude patterns" CLOSED 2018-07-05, [#2493](https://github.com/psf/black/issues/2493) "pre-commit hook fails when using `required-version`" CLOSED 2021-09-13.
- **performance** — [#1951](https://github.com/psf/black/issues/1951) "black performance scales non-linearly with the number of files to process" CLOSED 2021-02-01, [#2314](https://github.com/psf/black/issues/2314) "string_processing: Performance regression" OPEN 2021-06-07, [#1143](https://github.com/psf/black/issues/1143) "Improve the the blib2to3 grammar caching mechanism" CLOSED 2019-11-06, [#248](https://github.com/psf/black/issues/248) "Add command line option to ignore cache" CLOSED 2018-05-22.
- **embedded-language** — [#2345](https://github.com/psf/black/issues/2345) "Jupyter notebook support" CLOSED 2021-06-22, [#2446](https://github.com/psf/black/issues/2446) "black-jupyter aborted under pre-commit" CLOSED 2021-08-26, [#774](https://github.com/psf/black/issues/774) "does not ignore jupyter magic commands within # fmt: off/on blocks" CLOSED 2019-03-20.
- **other (how bugs are found)** — fuzzing: [#1913](https://github.com/psf/black/issues/1913) "Fix Bug exposed by Fuzz Tests" CLOSED 2021-01-08, [#1749](https://github.com/psf/black/issues/1749) "Fuzz tests fail when Python3.9." CLOSED 2020-10-08, [#1960](https://github.com/psf/black/issues/1960) "Fuzzer Discovery: … multi-line strings and newlines at the EOF" CLOSED 2021-02-03; corpus ("Primer"): [#2407](https://github.com/psf/black/issues/2407) "Create/Find a repo that uses every python syntax + Add to Primer" OPEN 2021-07-28.

**Relevance to hocon-fmt**
- idempotence **high** (we already refuse `UnstableOutput`; Black shows the class never ends and needs a generator, not examples).
- comment-loss **high** (our core promise; the sconfig blank-line defect is exactly their class).
- semantic-change **high** (their false negative is our `+=`/env-override defect, and #4683 is an output that silently means something else).
- parse-error-handling **high** (they crash or misparse where we refuse; the refusal is the right shape and should stay).
- whitespace-eol-encoding **high** (a leading blank line moving a decoding cookie is the shape of our blank-line loss: a layout change with a non-layout effect; the encoding half is already ours via `Refusal.NotUtf8`).
- check-exit-codes **high** for check (#5474 is a `--check` that disagrees with what a partial run did); partial-range **low** (we offer no ranges; if we ever do, their issues are the specification of the trap).
- comment-movement **medium** (HOCON comments have no semantics, but our promise counts them, and an include that moves across a field does change meaning).
- config-ignore **medium** (`.hocon-fmt.conf` plus the `.gitignore` walk is the same design; #2863 is the trap we avoided by deciding a config beats `.git`).
- performance **medium** (the cache idea already sits in [ideas.md](../../ideas.md), including Black's lesson that the key must cover every style flag).
- embedded-language **low** (no second language inside HOCON); line-width-layout **low** (sconfig decides width, not us); crash **high** by transfer (Black's crashes lose nothing because nothing was written — our staged write is the same guarantee).

**Probe**
- idempotence: `x = 1` then `x = ${?ENV}` at the root — expected `Refusal.BrokenOutput` (their class is "the second pass differs"; ours is the resolved banner growing).
- comment-loss: a one-line licence header, a blank line, then `a = 1` — expected refusal today (the 20-of-23 reference.conf shape); the playground keeps the comment, the CLI stays refused until sconfig releases the option.
- semantic-change: `e = ${g} { name = "east" }` — expected `Refusal.BrokenOutput`, and the same answer with `--separator :`.
- check-exit-codes: `hocon-fmt --check` twice on the same file — the three states must stay distinguishable: 0 clean, 1 would change, 2 unreadable, refusal never failing the run.
- whitespace-eol-encoding: a UTF-8 BOM plus CRLF and a trailing space — expected one format, then a clean `--check`.

**Development timeline**
- 22.1.0 (Jan 2022): first non-beta, and the release that introduced the stability policy — users had been burned by style churn in the betas.
- 23.1.0 (Jan 2023): first annual stable style, described as "improvements to our stable style" plus a call for feedback (CHANGES.md).
- 24.1.0 (Jan 2024): the 2024 stable style, prepared in [#4042](https://github.com/psf/black/issues/4042) "Setting the 2024 stable style" CLOSED 2023-11-13.
- 25.1.0 (Jan 2025): the 2025 stable style, prepared in [#4522](https://github.com/psf/black/issues/4522) "Setting the 2025 stable style" CLOSED 2024-12-04.
- 26.1.0 (Jan 2026): "Introduces the 2026 stable style (#4892)" (CHANGES.md), planned in [#4875](https://github.com/psf/black/issues/4875) "Setting the 2026 stable style" CLOSED 2025-11-29.
- 26.10.0 (Oct 2026): `--line-ranges` fixes, including the empty line after a docstring (#5312, CHANGES.md).
- The option debate is documented, not settled item by item: [#3087](https://github.com/psf/black/issues/3087) "Proposal: Shades of Black" CLOSED 2022-05-26, [#4123](https://github.com/psf/black/issues/4123) "Preview style feedback…" OPEN 2023-12-22, [#2135](https://github.com/psf/black/issues/2135) "Turn off magic trailing commas by default" OPEN 2021-04-26.

**Ideas to migrate**
- **A generator-based idempotence property for HOCON** (M): random-but-valid HOCON with comments, blank lines, includes, `+=` and `${?X}`; assert every result is either refused or a fixed point. User value: high — the only way to find the refusals we have not imagined.
- **`--required-version`** (S): parity with "one version everywhere"; a CI failure names the version that ran. User value: medium.
- **A cache with `--no-cache`** (S, already ideas.md's "Skip unchanged files"): Black's lesson is the key must include every style-relevant flag, and `--no-cache` must exist to debug it. User value: medium.
- **A scheduled corpus run over real configs** (M): the 1,650-file GitHub sweep as a repeatable job; Black's Primer is the model, and it is how their worst bugs were found. User value: high.

## Prettier (JavaScript, TypeScript, CSS, HTML, Markdown, …)

**Tool**: Prettier, [github.com/prettier/prettier](https://github.com/prettier/prettier), releases from early 2017
(its TypeScript support issue [#13](https://github.com/prettier/prettier/issues/13) "TypeScript Support" CLOSED 2017-01-10); opinion
is the product and the option set is frozen.

**Design choices that matter to us**
- "Prettier has a few options because of history. **But we won't add more of them.**" — option requests are closed without discussion, as are requests to preserve input line breaks ([option philosophy](https://raw.githubusercontent.com/prettier/prettier/main/docs/option-philosophy.md)).
- Correctness is the first requirement: "output valid code that has the exact same behavior as before formatting"; `--debug-check` flags suspected correctness changes ([rationale](https://raw.githubusercontent.com/prettier/prettier/main/docs/rationale.md), [CLI](https://raw.githubusercontent.com/prettier/prettier/main/docs/cli.md)).
- It refuses to sort or move imports: sorting is "potentially unsafe because of side effects … and makes it difficult to verify the most important correctness goal" (rationale).
- Comments: "Prettier tries its best to keep your comments roughly where they were"; magic comments such as `eslint-disable-next-line` may need moving by hand, and the docs recommend range- or statement-level disables instead (rationale) — the same hazard as a directive comment in any language.
- `--check` exits 0/1/2 and prints no diff ([#6885](https://github.com/prettier/prettier/issues/6885) "Add diff in output of --check (for CI use cases)" OPEN 2019-11-08, 121 comments); `--list-different` is the pipe-friendly variant ([CLI](https://raw.githubusercontent.com/prettier/prettier/main/docs/cli.md)).
- Ranges: `--range-start/--range-end` for editors; they are ignored when `--config-precedence=prefer-file` ([#13354](https://github.com/prettier/prettier/issues/13354) OPEN 2022-08-24).
- Ignore files: `.prettierignore` in gitignore syntax plus `.gitignore` by default; explicit paths are still ignored ([#15665](https://github.com/prettier/prettier/issues/15665) "CLI should prettify exact file paths passed in even if ignored" OPEN 2023-11-16); the walk does not honour them when recursing ([#11568](https://github.com/prettier/prettier/issues/11568) OPEN 2021-09-22).
- Config discovery searches up from the formatted file until a config is found ([configuration](https://raw.githubusercontent.com/prettier/prettier/main/docs/configuration.md)); `.editorconfig` is read and overridden by `.prettierrc`, and unlike the EditorConfig spec its search stops at the project root (same page).
- `--cache` keys on content or metadata; plugin versions are explicitly *not* keys ([CLI](https://raw.githubusercontent.com/prettier/prettier/main/docs/cli.md)).
- Plugins get `embed` for embedded languages, but the comment API is still a discussion ([#4290](https://github.com/prettier/prettier/issues/4290) OPEN 2018-04-09, [#5087](https://github.com/prettier/prettier/issues/5087) OPEN 2018-09-12).

**Bug classes**
- **semantic-change** — [#187](https://github.com/prettier/prettier/issues/187) "Removes parentheses in expressions with mixed operators" OPEN 2017-01-14 (237 comments), [#3805](https://github.com/prettier/prettier/issues/3805) "removes parentheses around conditional in ternary expression" OPEN 2018-01-24, [#3344](https://github.com/prettier/prettier/issues/3344) "Markdown: line break behaviour creates new elements and changes meaning…" CLOSED 2017-11-29, [#20096](https://github.com/prettier/prettier/issues/20096) "TS: Statement after top-level `await` is duplicated with `typescript` parser" OPEN 2026-09-17.
- **comment-movement** — [#5411](https://github.com/prettier/prettier/issues/5411) "Formatting breaks @ts-ignore comments" OPEN 2018-11-08, [#807](https://github.com/prettier/prettier/issues/807) "Keep comments that are near an `if`'s condition closer to that condition" CLOSED 2017-02-24, [#19420](https://github.com/prettier/prettier/issues/19420) "The new oxidized flow parser breaks comments" OPEN 2026-06-23.
- **idempotence** — [#2803](https://github.com/prettier/prettier/issues/2803) "Idempotency / Unstable method chain" OPEN 2017-09-13, [#18078](https://github.com/prettier/prettier/issues/18078) "prettier might require 2 passes to complete" OPEN 2025-10-15, [#11665](https://github.com/prettier/prettier/issues/11665) "Non idempotent output (comment before closing parenthesis)" OPEN 2021-10-10, [#19271](https://github.com/prettier/prettier/issues/19271) "Unstable comment around parenthesized assignment" OPEN 2026-06-02, [#20052](https://github.com/prettier/prettier/issues/20052) "CSS: … not idempotent — extra space added on every format" CLOSED 2026-09-13, [#19847](https://github.com/prettier/prettier/issues/19847) "markdown: row indented GFM table rendering idempotency" OPEN 2026-08-14.
- **AST-equivalence checking and its gaps** — the check found real bugs: [#1558](https://github.com/prettier/prettier/issues/1558) "(typescript) React Literals fail `--debug-check`" CLOSED 2017-05-09, [#1557](https://github.com/prettier/prettier/issues/1557) "ObjectTypeProperty fails `--debug-check`" CLOSED 2017-05-09, [#1559](https://github.com/prettier/prettier/issues/1559) "Template Literal expression comments moving outside expression" CLOSED 2017-05-09; and it is not run everywhere: [#7503](https://github.com/prettier/prettier/issues/7503) "Proposal: Add new AST COMPARE test" OPEN 2020-01-31, [#8960](https://github.com/prettier/prettier/issues/8960) "Run AST_COMPARE test for verifyParsers too" CLOSED 2020-08-11, [#3512](https://github.com/prettier/prettier/issues/3512) "Proposal: `--check-ast --write`" OPEN 2017-12-18.
- **check-exit-codes** — [#6885](https://github.com/prettier/prettier/issues/6885) (above), [#4144](https://github.com/prettier/prettier/issues/4144) "CLI: --list-different + --write = Exit status 1 if something was modified" CLOSED 2018-03-14, [#11908](https://github.com/prettier/prettier/issues/11908) "Javascript errors inside markdown should return status code 1 (currently the error is ignored)" OPEN 2021-12-02.
- **config-ignore** — [#8303](https://github.com/prettier/prettier/issues/8303) "Don't look for a configuration file outside the project directory" OPEN 2020-05-13, [#4081](https://github.com/prettier/prettier/issues/4081) "handle .prettierignore location like .gitignore" OPEN 2018-03-01, [#15438](https://github.com/prettier/prettier/issues/15438) "Cannot Force Inclusion Of Directory/Files That Are In `.gitignore` File" OPEN 2023-09-20, [#10395](https://github.com/prettier/prettier/issues/10395) "add warning about file ignored and cli flag to enforce formatting" OPEN 2021-02-22, [#15255](https://github.com/prettier/prettier/issues/15255) "Correct documentation on the `editorconfig` option." OPEN 2023-08-17.
- **embedded-language** — [#5588](https://github.com/prettier/prettier/issues/5588) "Disable embedded language formatting by default and make it configurable" OPEN 2018-12-03, [#6517](https://github.com/prettier/prettier/issues/6517) "Support Helm's template + yaml language" OPEN 2019-09-24, [#12209](https://github.com/prettier/prettier/issues/12209) "MDX 3" OPEN 2022-02-01, [#11908](https://github.com/prettier/prettier/issues/11908) (above).
- **crash** — [#5004](https://github.com/prettier/prettier/issues/5004) "Prettier crashes on formating large files" OPEN 2018-08-22, [#4306](https://github.com/prettier/prettier/issues/4306) "Code blocks in markdown list items with two or more empty lines may crash parser" CLOSED 2018-04-12, [#15073](https://github.com/prettier/prettier/issues/15073) "Error: \"highest level\" was not printed" CLOSED 2023-07-10.
- **performance** — [#4776](https://github.com/prettier/prettier/issues/4776) "Performance on big files" OPEN 2018-06-30, [#4980](https://github.com/prettier/prettier/issues/4980) "Feature Request: Parallel/Clustered Prettier" OPEN 2018-08-14, [#4801](https://github.com/prettier/prettier/issues/4801) "formatWithCursor performance bottleneck" OPEN 2018-07-03, [#5853](https://github.com/prettier/prettier/issues/5853) "Add cache option to avoid processing unchanged files" CLOSED 2019-02-12, [#13032](https://github.com/prettier/prettier/issues/13032) "node_modules/.cache/prettier" CLOSED 2022-06-20.
- **other (the style debate they have to live with)** — [#7475](https://github.com/prettier/prettier/issues/7475) "Change `useTabs` to `true` by default" OPEN 2020-01-30 (661 comments), [#3503](https://github.com/prettier/prettier/issues/3503) "Prettier 2.0 (old)" CLOSED 2017-12-16, [#6921](https://github.com/prettier/prettier/issues/6921) "Revisit function literal heuristic introduced in Prettier 1.19" OPEN 2019-11-11.

**Relevance to hocon-fmt**
- comment-movement **high** (a moved comment is our `MovedInclude`/`LostComment`; Prettier documents it as unavoidable and recommends comments whose meaning does not depend on position — HOCON has no such comments, so refusal is our only tool).
- idempotence **high** (their 2017 issue is still open: a fixed-point refusal is worth more than a fix).
- semantic-change **high** (their parentheses class is our substitution/append class: output that parses but means something else).
- check-exit-codes **high** (0/1/2 and a diff behind a flag is the shape we chose; #6885 says CI users wanted the diff, which ideas.md already gives us).
- config-ignore **high** (the `.gitignore` walk, the "explicit path wins" question we answered, and config-outside-the-project are all live for us).
- ordering **high** where includes are involved (Prettier sorts nothing precisely because it cannot verify safety; we must keep refusing to sort).
- performance **medium** (their cache design and the "plugin versions are not cache keys" gotcha apply if we cache).
- embedded-language **low** (HOCON is one language; a `.conf` in a Markdown fence would be the analog and we have no such channel); line-width-layout **low**.

**Probe**
- idempotence: `a = [1, 2]` with a comment before the closing `]` (sconfig drops it — expect refusal, not silent loss); if a future sconfig keeps it, a second pass must be a fixed point.
- comment-movement: `include "x.conf", a = 1` — expected `Refusal.MovedInclude`, never a silent swap.
- check-exit-codes: `--check` on a file a write would change exits 1; on a refused file exits 0 with a warning; on a missing file exits 2 — all three distinguishable.
- config-ignore: a `.conf` listed in `.gitignore` and named on the command line is examined (our documented choice); a directory walk skips it.
- semantic-change: `path = ${path}":d"` — expected `Refusal.BrokenOutput`.

**Development timeline**
- 1.x (2017): rapid language expansion; the AST-diff test found comment and parenthesis bugs as soon as TypeScript landed ([#1557](https://github.com/prettier/prettier/issues/1557), [#1558](https://github.com/prettier/prettier/issues/1558), [#1559](https://github.com/prettier/prettier/issues/1559), all May 2017).
- 2.0 planned from [#3503](https://github.com/prettier/prettier/issues/3503) "Prettier 2.0 (old)" CLOSED 2017-12-16 and shipped 2020: a major version as the vehicle for style changes users opt into — the alternative to Black's annual edition.
- The option freeze ([option philosophy](https://raw.githubusercontent.com/prettier/prettier/main/docs/option-philosophy.md)): "Option requests aren't accepted anymore."
- 3.0 (2023): plugin resolution changed and broke setups ([#15025](https://github.com/prettier/prettier/issues/15025) "Can't use NODE_PATH to locate plugins since 3.0" OPEN 2023-07-06); caching landed with it ([CLI](https://raw.githubusercontent.com/prettier/prettier/main/docs/cli.md)).
- 3.9 (2026): an idempotence regression wave in embedded template literals ([#19518](https://github.com/prettier/prettier/issues/19518) CLOSED 2026-07-01, [#19541](https://github.com/prettier/prettier/issues/19541) CLOSED 2026-07-06).
- The `--check` gap ([#6885](https://github.com/prettier/prettier/issues/6885), open since 2019 with 121 comments) is the clearest evidence that a check mode without a diff leaves CI users asking for one.

**Ideas to migrate**
- **A diff on the refusal path** (S): we already print the formatted text; locating the refusal's reason as a diff line is what their users keep asking for. User value: medium.
- **Range formatting only with a documented best-effort contract** (M): if we ever offer it, copy their warning text and their test list; neither is free. User value: medium (editors).
- **A comment-position doctrine in docs** (S): Prettier writes down which comments may move and how to avoid depending on position; our equivalent sentence already exists implicitly in [limitations.md](../../limitations.md). User value: medium.
- **The option freeze as the case against a big option set** (S): we plan a configurable style; this essay is the argument for reviewing each option as a liability. User value: medium.

## rustfmt (Rust)

**Tool**: rustfmt, [github.com/rust-lang/rustfmt](https://github.com/rust-lang/rustfmt), tracker entries from March 2015;
"designed to be very configurable" but with a documented list of places the stability guarantee does
not reach, and style editions to move the style without breaking users.

**Design choices that matter to us**
- The README's Limitations list is the honesty model: no guarantees for programs that do not parse, for macros, for comments and comment-internal code, for program fragments, for non-ASCII, and "Bugs in Rustfmt … we do not consider bug fixes to break our stability guarantees" ([README](https://raw.githubusercontent.com/rust-lang/rustfmt/master/README.md)).
- Style editions: `style_edition` selects formatting behaviour and is inferred from the language edition; `version = "Two"` is soft-deprecated for it, and the option was stabilized in 2024 ([README](https://raw.githubusercontent.com/rust-lang/rustfmt/master/README.md), [CHANGELOG 1.8.0](https://raw.githubusercontent.com/rust-lang/rustfmt/master/CHANGELOG.md)).
- Stable vs unstable options: unstable ones need a nightly toolchain and `unstable_features = true`; most of its hardest problems sit in `[unstable option]` issues ([README](https://raw.githubusercontent.com/rust-lang/rustfmt/master/README.md), [#3387](https://github.com/rust-lang/rustfmt/issues/3387) OPEN 2019-02-13).
- `--check` exits 0/1; in other modes a parse or internal error is exit 1 while success is 0 whether or not it changed anything ([README](https://raw.githubusercontent.com/rust-lang/rustfmt/master/README.md)); "Audit exit codes" was its own early issue ([#1977](https://github.com/rust-lang/rustfmt/issues/1977) CLOSED 2017-09-18).
- Skipping: `#[rustfmt::skip]` on an item, `::macros(...)`, `::attributes(...)`; the `ignore` configuration was stabilized after years unstable ([README](https://raw.githubusercontent.com/rust-lang/rustfmt/master/README.md), [#3243](https://github.com/rust-lang/rustfmt/issues/3243) "Stabilize the `ignore` configuration" CLOSED 2018-12-10).
- Ranges exist but are unstable (`file_lines`), and they changed whitespace outside the range ([#3397](https://github.com/rust-lang/rustfmt/issues/3397) OPEN 2019-02-13, [#5136](https://github.com/rust-lang/rustfmt/issues/5136) "file-lines changes whitespace outside of given range" CLOSED 2021-12-13).
- Config discovery: `rustfmt.toml` or `.rustfmt.toml` in the project or any parent; `cargo fmt` reads `edition` from `Cargo.toml`, so direct `rustfmt` and `cargo fmt` disagree unless both are configured ([README](https://raw.githubusercontent.com/rust-lang/rustfmt/master/README.md)).
- Emit modes: `--emit files|stdout|diff|json`; `--print-config default`; stdin to stdout ([README](https://raw.githubusercontent.com/rust-lang/rustfmt/master/README.md)).
- Safety is tested in its own tree two ways: `idempotence_tests` require every file in `tests/target` to be unchanged by a run, and rustfmt formats itself as part of the suite ([src/test/mod.rs](https://raw.githubusercontent.com/rust-lang/rustfmt/master/src/test/mod.rs)).

**Bug classes**
- **idempotence** — [#6613](https://github.com/rust-lang/rustfmt/issues/6613) "Non-idempotency involving return expr in match arm, macros and long string literals" OPEN 2025-07-23, [#5193](https://github.com/rust-lang/rustfmt/issues/5193) "My code needs two passes to be formatted correctly" OPEN 2022-01-26, [#4050](https://github.com/rust-lang/rustfmt/issues/4050) "Rustfmt changes meaning of closure, also not idempotent" OPEN 2020-02-12, [#6338](https://github.com/rust-lang/rustfmt/issues/6338) "max_width makes short lines longer" OPEN 2024-09-19.
- **comment-loss** — [#5464](https://github.com/rust-lang/rustfmt/issues/5464) "Rustfmt silently removes comments after enum/struct field" OPEN 2022-07-24, [#5695](https://github.com/rust-lang/rustfmt/issues/5695) "rustfmt removes comment in macro invocation" OPEN 2023-02-17, [#4082](https://github.com/rust-lang/rustfmt/issues/4082) "removes empty newlines between blocks of module-level attributes" OPEN 2020-03-14, [#3277](https://github.com/rust-lang/rustfmt/issues/3277) "Trailing comma removed from multiline attribute" OPEN 2019-01-03, [#4668](https://github.com/rust-lang/rustfmt/issues/4668) "Exclude block-style comments in wrap_str/filter_normal_code check" OPEN 2021-01-24.
- **comment-movement** — [#3127](https://github.com/rust-lang/rustfmt/issues/3127) "Comments on `extern crates` that are reordered get misplaced" OPEN 2018-10-22, [#4120](https://github.com/rust-lang/rustfmt/issues/4120) "Incorrect comment indent inside if/else" CLOSED 2020-04-13.
- **semantic-change** — [#2479](https://github.com/rust-lang/rustfmt/issues/2479) "`cargo fmt` removes enum variants" CLOSED 2018-02-21, [#4621](https://github.com/rust-lang/rustfmt/issues/4621) "Removing empty type parameter list results in invalid Rust code" OPEN 2021-01-03, [#6159](https://github.com/rust-lang/rustfmt/issues/6159) "Type ascription builtin is silently replaced by rustfmt(?)" CLOSED 2024-05-02, [#7011](https://github.com/rust-lang/rustfmt/issues/7011) "Merge `.. .` tokens producing invalid syntax" CLOSED 2026-08-04, [#5691](https://github.com/rust-lang/rustfmt/issues/5691) "duplicating `where` clauses that contains (unused) braces" CLOSED 2023-02-16.
- **parse-error-handling** — [#4126](https://github.com/rust-lang/rustfmt/issues/4126) "rustfmt deletes lines instead of detecting a syntax error" CLOSED 2020-04-18, [#3779](https://github.com/rust-lang/rustfmt/issues/3779) "gracefully handle recoverable parser errors in ignored files" CLOSED 2019-09-05.
- **check-exit-codes** — [#3871](https://github.com/rust-lang/rustfmt/issues/3871) "`--check` silently ignored when formatting from stdin" CLOSED 2019-10-18, [#5364](https://github.com/rust-lang/rustfmt/issues/5364) "rustfmt fails on an empty file; --check's diff doesn't result in properly formatted file" OPEN 2022-06-02, [#2787](https://github.com/rust-lang/rustfmt/issues/2787) "cargo fmt --all succeeds but subsequent cargo fmt --all -- --check fails" CLOSED 2018-06-15, [#3799](https://github.com/rust-lang/rustfmt/issues/3799) "cargo fmt and cargo fmt --check don't agree on bindgen" OPEN 2019-09-17, [#2832](https://github.com/rust-lang/rustfmt/issues/2832) "regression from previous stable: cargo fmt -- --write-mode diff return 0" CLOSED 2018-07-08.
- **partial-range** — [#3397](https://github.com/rust-lang/rustfmt/issues/3397) and [#5136](https://github.com/rust-lang/rustfmt/issues/5136) (above), [#1324](https://github.com/rust-lang/rustfmt/issues/1324) "Add feature to only changes that were already modified (git)" OPEN 2017-02-19.
- **config-ignore** — [#6264](https://github.com/rust-lang/rustfmt/issues/6264) "Feature Request: Ignore `rustfmt.toml`" OPEN 2024-08-04, [#4660](https://github.com/rust-lang/rustfmt/issues/4660) "Recursive `--config-path` doesn't recurse" CLOSED 2021-01-21, [#4286](https://github.com/rust-lang/rustfmt/issues/4286) "Stability Guarantee/Version Strategy" CLOSED 2020-06-29, [#5577](https://github.com/rust-lang/rustfmt/issues/5577) "Catalog `Version=Two` formatting differences" OPEN 2022-10-28, [#6954](https://github.com/rust-lang/rustfmt/issues/6954) "`rustfmt::skip` sometimes ignored when using `error_on_line_overflow` …" OPEN 2026-06-26.
- **ordering** — [#5083](https://github.com/rust-lang/rustfmt/issues/5083) "[unstable option] `group_imports`" OPEN 2021-11-14, [#3127](https://github.com/rust-lang/rustfmt/issues/3127) (above): reordering is where comments get lost.
- **performance** — [#5752](https://github.com/rust-lang/rustfmt/issues/5752) "RustFmt takes entire ram when formatting simple(broken) file" OPEN 2023-04-14, [#6091](https://github.com/rust-lang/rustfmt/issues/6091) "Implement concurrent formatting" OPEN 2024-02-24.
- **crash** — [#5465](https://github.com/rust-lang/rustfmt/issues/5465) "ExplicitBug crash on `m!(a. X::Y)`" OPEN 2022-07-25, [#7033](https://github.com/rust-lang/rustfmt/issues/7033) "[ICE]: `Request to format inverted span`" OPEN 2026-08-18, [#5876](https://github.com/rust-lang/rustfmt/issues/5876) "Unicode Character + Trailing Whitespace leads to panic in annotate-snippets dependency" CLOSED 2023-08-02.
- **other (corpus; partial application)** — [#2692](https://github.com/rust-lang/rustfmt/issues/2692) "Add corpus of crates to rustfmt's CI" CLOSED 2018-05-09, [#3008](https://github.com/rust-lang/rustfmt/issues/3008) "cargo fmt --all completely stops formatting when failing on a single file" OPEN 2018-09-10.

**Relevance to hocon-fmt**
- idempotence **high** (same refusal we already make; their `tests/target` fixed-point suite is the cheapest version of our idea).
- comment-loss **high** (their silent losses are our `LostComment`; that they are *not* refusals is exactly the difference our contract makes).
- semantic-change **high** (removing enum variants or emitting invalid Rust is "corrupt, not refuse"; they found these after release because nothing compared output to input).
- parse-error-handling **high** (deleting lines on a syntax error is the harshest failure story in this family).
- check-exit-codes **high** (`--check` disagreeing with a fix run is our `--check` contract too: the check must never disagree with what a write does).
- crash **high** (a crash must never leave a half-written file; our staged write exists for this, and their RAM blow-up argues for a resource guard).
- partial-range **medium** (we offer none; their warning applies if we add one); config-ignore **medium** (style_edition is the versioned-style mechanism we may need before 1.0; "skip ignored under error flags" is our "refusal never fails a run").
- ordering **medium** (they reorder imports only under an unstable option and still misplace comments; our include-order refusal is stricter and right).
- performance **medium**; whitespace-eol-encoding **low** (no such class in their tracker's top hits).

**Probe**
- idempotence: format the whole `examples/` tree twice and compare — expected zero differences (the property belongs in a suite, not a spot check).
- comment-loss: `a = 1 # note` inside an object where the comment would end up before the closing brace — expected refusal; if kept in future, a second pass must keep it in place.
- semantic-change: `x = 1` then `x = ${?Y}` (their shape: valid output, different program) — expected `Refusal.UnstableOutput` at the root and a banner-free refusal elsewhere.
- check-exit-codes: `--check` exits 1 when a write would change the file, 0 on a refused file; after a write, `--check` is clean (their #2787 shape).
- crash: a 40-deep nested object on Scala.js (31 is fine, 32 breaks) — expected `Refusal.BrokenOutput`, never a truncated file; kill the process mid-write and check the original is intact.

**Development timeline**
- Early "stability guarantee / version strategy" discussion settled into a policy where bug fixes do not count as style changes ([#4286](https://github.com/rust-lang/rustfmt/issues/4286) CLOSED 2020-06-29).
- Corpus of crates in CI ([#2692](https://github.com/rust-lang/rustfmt/issues/2692) CLOSED 2018-05-09): the differential test that finds what unit tests cannot.
- 1.6.0 (2023-07-02) and 1.7.0 (2023-10-22): the nightly-option era; the 1.7.x CHANGELOG is mostly fixes to unstable options ([CHANGELOG](https://raw.githubusercontent.com/rust-lang/rustfmt/master/CHANGELOG.md)).
- 1.8.0 (2024-09-20): `version` soft-deprecated for `style_edition`, per RFC 3338; "Users are encouraged to configure `style_edition` … the value can also be specified via the cli" (CHANGELOG).
- 1.9.0 (2026-02-26): `style_edition=2024` and the option itself stabilized (CHANGELOG).
- 1.10.0 (2026-07-21): continued style-edition gating of new formatting (CHANGELOG).
- The idempotence and comment issues above stay open across all of it: the class is not "fixed once".

**Ideas to migrate**
- **A `tests/target`-style fixed-point suite over our golden files** (S): format each golden output and require byte equality. User value: high (it is what rustfmt's suite does first).
- **A style-stability statement** (S): before 1.0, write down what may change; rustfmt's editions are the heavyweight answer, Black's annual release the lightweight one. User value: high (the promise plugin users need).
- **Docs that list where the guarantee does not reach** (S): [limitations.md](../../limitations.md) already does this; rustfmt's README shows the genre can be short and still credible. User value: medium.
- **A resource limit on pathological input** (M): their RAM blow-up is a pre-commit hazard; a size or depth cap that becomes a refusal is in our spirit. User value: medium.

## clang-format (C, C++, Java, JavaScript, …)

**Tool**: clang-format, part of [llvm/llvm-project](https://github.com/llvm/llvm-project), shipping with Clang;
its tracker entries go back to 2014 ([#21611](https://github.com/llvm/llvm-project/issues/21611) "Attribute hidden in macro causes unnecessary line break" CLOSED 2014-10-10).
Everything is a style option, the style file is discovered upward, and there is no versioned-style
mechanism.

**Design choices that matter to us**
- Config discovery: `.clang-format`/`_clang-format` in a parent directory of the file, `--style=file` the default, `--fallback-style` (LLVM) when none is found or `none` to skip, `--assume-filename` for stdin ([ClangFormat.html](https://clang.llvm.org/docs/ClangFormat.html)).
- `.clang-format-ignore` files exist, patterns relative to the file's directory, with the usual "lower level voids higher" rule ([ClangFormat.html](https://clang.llvm.org/docs/ClangFormat.html)).
- Disabling: `// clang-format off` / `on` (C-style comments too) and `DisableFormat`; unmatched pairs are not detected ([#53694](https://github.com/llvm/llvm-project/issues/53694) OPEN 2022-02-10).
- Ranges: `--lines=start:end` plus `--offset/--length`; `git-clang-format` and `clang-format-diff.py` for diffs; `--dry-run` plus `--Werror` for CI; `--fail-on-incomplete-format` exists ([ClangFormat.html](https://clang.llvm.org/docs/ClangFormat.html)).
- Line endings were once silently changed by `-i` ([#25373](https://github.com/llvm/llvm-project/issues/25373) "clang-format -i replaces LF by CRLF" CLOSED 2015-09-30); the style now has `DeriveLineEnding`/`UseCRLF` (same page).
- Language detection from the file name is a real trap: `.h` defaulting to C++ caused a breaking change in 20.1.0 that needed an automatic fallback ([#132832](https://github.com/llvm/llvm-project/issues/132832) CLOSED 2025-03-24).
- Sorting includes is a default-on behaviour that reorders text users consider semantic; the request to disable it entirely is old ([#41085](https://github.com/llvm/llvm-project/issues/41085) "clang-format: disable include sorting" CLOSED 2019-05-04).
- There is no global check mode; `--dry-run --Werror` is the CI spelling, and `-n`/`--dry-run` prints what would change ([ClangFormat.html](https://clang.llvm.org/docs/ClangFormat.html)).
- Fuzzing exists for crashes only: the libFuzzer target formats random input with a fixed Google style and discards the result, with the comment "Output must be checked, as otherwise we crash" ([ClangFormatFuzzer.cpp](https://raw.githubusercontent.com/llvm/llvm-project/main/clang/tools/clang-format/fuzzer/ClangFormatFuzzer.cpp)); its unit tests contain no idempotence assertion (a grep of the fetched [FormatTest.cpp](https://raw.githubusercontent.com/llvm/llvm-project/main/clang/unittests/Format/FormatTest.cpp) for "idempot" finds none).
- Editor/IDE integration has its own section in the docs, with plugins for CLion/VS Code/etc. ([ClangFormat.html](https://clang.llvm.org/docs/ClangFormat.html)).

**Bug classes**
- **idempotence** — the defining class, with regressions *per release*: [#24102](https://github.com/llvm/llvm-project/issues/24102) "Unstable format results: each format results in new output." OPEN 2015-06-02, [#51808](https://github.com/llvm/llvm-project/issues/51808) "not idempotent with long comment containing tabs" CLOSED 2021-11-10, [#107616](https://github.com/llvm/llvm-project/issues/107616) "`clang-format` is not idempotent on certain files" CLOSED 2024-09-06, [#157976](https://github.com/llvm/llvm-project/issues/157976) "not idempotent with \"comment alignment\"" OPEN 2025-09-11, [#165185](https://github.com/llvm/llvm-project/issues/165185) "not idempotent when a struct field with an all-caps typedef name wraps due to long comment" OPEN 2025-10-27, [#118334](https://github.com/llvm/llvm-project/issues/118334) "19 idempotent regression from 18 for #define within a function" CLOSED 2024-12-02, [#86550](https://github.com/llvm/llvm-project/issues/86550) "18 idempotent regression with #define within an initializer" CLOSED 2024-03-25, [#194717](https://github.com/llvm/llvm-project/issues/194717) "22 idempotent regression when using \"AlignConsecutiveDeclarations: Consecutive\"" CLOSED 2026-04-28, [#147341](https://github.com/llvm/llvm-project/issues/147341) "Non-idempotency on 20.1.7" CLOSED 2025-07-07, [#58202](https://github.com/llvm/llvm-project/issues/58202) "Running clang-format once produces different output from running it twice, on some inputs" CLOSED 2022-10-06, [#26345](https://github.com/llvm/llvm-project/issues/26345) "Formatting is not idempotent with 'MaxEmptyLinesToKeep' being 0" CLOSED 2015-12-30.
- **comment-movement (integrity)** — [#52649](https://github.com/llvm/llvm-project/issues/52649) "breaks comment integrity with SpacesInLineCommentPrefix" CLOSED 2021-12-13, [#33997](https://github.com/llvm/llvm-project/issues/33997) "messes up comment indentations" OPEN 2017-09-17, [#55487](https://github.com/llvm/llvm-project/issues/55487) "no space before trailing comment" CLOSED 2022-05-15, [#57463](https://github.com/llvm/llvm-project/issues/57463) "do not align #endif comment" CLOSED 2022-08-31.
- **the off/on pragma** — [#54334](https://github.com/llvm/llvm-project/issues/54334) "Disabling Formatting on a one line" CLOSED 2022-03-11, [#53694](https://github.com/llvm/llvm-project/issues/53694) (above), [#40246](https://github.com/llvm/llvm-project/issues/40246) "Clang-Format OFF/ON not respected when using C Style comments" CLOSED 2019-02-28, [#26550](https://github.com/llvm/llvm-project/issues/26550) "Corner case with the 'clang-format off' directive and 'AlignTrailingComments' being 'true'" OPEN 2016-01-16.
- **partial-range** — [#52993](https://github.com/llvm/llvm-project/issues/52993) "clang-format -lines removes a line outside of its range" CLOSED 2022-01-04, [#146036](https://github.com/llvm/llvm-project/issues/146036) "rejects formatting with -length=0" CLOSED 2025-06-27, [#56352](https://github.com/llvm/llvm-project/issues/56352) "crash with --lines and `CompactNamespaces: true NamespaceIndentation: All`" CLOSED 2022-07-02, [#60153](https://github.com/llvm/llvm-project/issues/60153) "git-clang-format: InsertBraces on a diff does not apply braces to entire if-else-if-else." OPEN 2023-01-19, [#32854](https://github.com/llvm/llvm-project/issues/32854) "Assertion failed: Shift >= 0 …" CLOSED 2017-06-19.
- **semantic-change** — [#45728](https://github.com/llvm/llvm-project/issues/45728) "breaks stringized macro argument" CLOSED 2020-06-18, [#35518](https://github.com/llvm/llvm-project/issues/35518) "removes space between macro name and definition in parens (thereby breaking the code)" CLOSED 2018-01-31, [#61654](https://github.com/llvm/llvm-project/issues/61654) "clang-format corrupts the code" CLOSED 2023-03-23, [#53409](https://github.com/llvm/llvm-project/issues/53409) "breaks compile with braced initialization of lambda" CLOSED 2022-01-25, [#47881](https://github.com/llvm/llvm-project/issues/47881) "Thinks Minus Is Negative" CLOSED 2020-12-17.
- **include-import-analog (sorting)** — [#41085](https://github.com/llvm/llvm-project/issues/41085) (above), [#58284](https://github.com/llvm/llvm-project/issues/58284) "does not allow main header file to be at bottom" OPEN 2022-10-11, [#27008](https://github.com/llvm/llvm-project/issues/27008) "does not recognize full path header … as corresponding header when sorting" CLOSED 2016-02-16, [#39735](https://github.com/llvm/llvm-project/issues/39735) "main include does not work for angle bracket includes" OPEN 2019-01-21, [#38995](https://github.com/llvm/llvm-project/issues/38995) "SortIncludes should support \"@import\" lines in Objective-C" OPEN 2018-11-13, [#27416](https://github.com/llvm/llvm-project/issues/27416) "sorts includes even in files where it should be disabled completely" CLOSED 2016-03-23.
- **crash / resource exhaustion** — [#23426](https://github.com/llvm/llvm-project/issues/23426) "fuzz clang-format" OPEN 2015-03-28 (the fuzzing request, a decade before the idempotence classes were closed), [#160313](https://github.com/llvm/llvm-project/issues/160313) "clang-format 21.1.2 out of memory" OPEN 2025-09-23, [#168317](https://github.com/llvm/llvm-project/issues/168317) "clang-format-20 was terminated by OOM-killer while formatting large c-header" OPEN 2025-11-17, [#184040](https://github.com/llvm/llvm-project/issues/184040) "LLVM ERROR: out of memory …" CLOSED 2026-03-01, [#157405](https://github.com/llvm/llvm-project/issues/157405) "clang-format segfaults with a stack dump" CLOSED 2025-09-08, [#218172](https://github.com/llvm/llvm-project/issues/218172) "`clang-format` crashes my macOS (M3 Pro; 36GB of RAM)" OPEN 2026-08-23, [#53880](https://github.com/llvm/llvm-project/issues/53880) "crashes when formatting LLVM itself" CLOSED 2022-02-16.
- **whitespace-eol-encoding** — [#25373](https://github.com/llvm/llvm-project/issues/25373) (above), [#34870](https://github.com/llvm/llvm-project/issues/34870) "DOS line endings (\r\n) result in incorrect output with clang-format" CLOSED 2017-12-04, [#224571](https://github.com/llvm/llvm-project/issues/224571) "[clang-format] CRLF changes wrapping around a multiline raw string at ColumnLimit" OPEN 2026-09-18.
- **config-ignore** — [#52975](https://github.com/llvm/llvm-project/issues/52975) "[clang-format] Support .clang-format-ignore file" CLOSED 2022-01-04, [#50445](https://github.com/llvm/llvm-project/issues/50445) ".editorconfig" OPEN 2021-07-15, [#167673](https://github.com/llvm/llvm-project/issues/167673) "assumes C++ language by default for .h files …" OPEN 2025-11-12, [#132832](https://github.com/llvm/llvm-project/issues/132832) (above).
- **line-width-layout** — [#54354](https://github.com/llvm/llvm-project/issues/54354) "gives wonky indentation" OPEN 2022-03-12, [#192714](https://github.com/llvm/llvm-project/issues/192714) "UseTab = AlignWithSpaces ignored for ternary operator and/or parantheses" OPEN 2026-04-17, [#68079](https://github.com/llvm/llvm-project/issues/68079) "Formatting regression for member function pointers using aligned assignment and declaration" CLOSED 2023-10-03.

**Relevance to hocon-fmt**
- idempotence **high** (per-release regressions are the argument for our fixed-point refusal: it catches what a test suite missed, including in released versions).
- semantic-change **high** (macros are their includes: a construct whose meaning depends on exact text — the reason we mask includes and refuse moved ones).
- partial-range **high** as a warning if we ever offer ranges (`-lines` deleting a line outside the range, and the git-clang-format brace bug, are what a best-effort range does to files).
- include-import-analog **high** (SortIncludes is a default-on reorder; our "sorting fields would move includes across them, and is never offered" is the stricter, correct answer).
- whitespace-eol-encoding **medium** (their CRLF bug is our `\n` normalization territory: an `-i` that rewrites line endings is a silent whole-file diff).
- crash/performance **medium** (OOM-killed hooks and crashes on large inputs; our Scala.js depth limit is the same class of guard).
- config-ignore **medium** (`.clang-format-ignore` and the `.editorconfig` request show users want a style file and an ignore file; we have `.hocon-fmt.conf` and `.gitignore`).
- comment-movement **medium** (their integrity bugs share our "a comment is data" view, one step weaker: they tolerate whitespace changes to comments, we count any loss).
- line-width-layout **low**; embedded-language **low** (one leaked case, [#110727](https://github.com/llvm/llvm-project/issues/110727) "clang-format 19 breaks ipynb files" CLOSED 2024-10-01); check-exit-codes **medium** (no check mode of their own — `--dry-run --Werror` is the spelling; our `--check` should stay a first-class flag, not two flags composed).

**Probe**
- idempotence: format `examples/catalogue/messy` twice with `--check` between — expected a fixed point and exit 0 the second time.
- semantic-change: `o { include "x.conf" }` then `o : 5` — expected `Refusal.LostInclude` (their macro class: the include must not vanish with the replaced object).
- partial-range: none to run; if a range flag is ever added, the first test must be "lines outside the range are byte-identical" (their #52993).
- include-import-analog: `include "b.conf"` after `a = 1` and before `a.c = 2` — expected `Refusal.MovedInclude`.
- whitespace-eol-encoding: a CRLF file whose last line has no newline — expected `\n` plus a trailing newline, and a clean `--check` on the second run.
- crash: the 300 KB generated config the benchmarks build — expected a result, not a timeout; record it in `scripts/bench.py`.

**Development timeline**
- 2014–2016: the tool spreads with Clang; idempotence and comment-integrity bugs are filed from the start ([#26345](https://github.com/llvm/llvm-project/issues/26345) 2015, [#24102](https://github.com/llvm/llvm-project/issues/24102) 2015, [#23426](https://github.com/llvm/llvm-project/issues/23426) 2015).
- 2016–2019: the include-sorting model shows its cost — sorting where disabled, main-header detection failing, ObjC `@import` unsupported ([#27416](https://github.com/llvm/llvm-project/issues/27416), [#27008](https://github.com/llvm/llvm-project/issues/27008), [#38995](https://github.com/llvm/llvm-project/issues/38995)).
- 2021–2022: `.clang-format-ignore` support is added ([#52975](https://github.com/llvm/llvm-project/issues/52975) CLOSED 2022-01-04), and the off/on directive gets a dedicated fix ([#54334](https://github.com/llvm/llvm-project/issues/54334) CLOSED 2022-03-11).
- 2024: release-to-release idempotence regressions (clang-format 18, [#86550](https://github.com/llvm/llvm-project/issues/86550)/[#86539](https://github.com/llvm/llvm-project/issues/86539) CLOSED 2024-03-25) — the class that motivates pinning a clang-format version in CI.
- 2025: language detection for `.h` fixed by falling back when C parsing fails ([#132832](https://github.com/llvm/llvm-project/issues/132832) CLOSED 2025-03-24); memory complaints grow ([#160313](https://github.com/llvm/llvm-project/issues/160313), [#168317](https://github.com/llvm/llvm-project/issues/168317)).
- 2026: another release regression ([#194717](https://github.com/llvm/llvm-project/issues/194717) CLOSED 2026-04-28) and a macOS crash ([#218172](https://github.com/llvm/llvm-project/issues/218172) OPEN 2026-08-23): the classes never close.

**Ideas to migrate**
- **Never sort, and say why in one line** (S): Prettier and clang-format are the two data points; our [limitations.md](../../limitations.md) has the sentence — keep it and cite the include-order check. User value: medium.
- **A fixed-point assertion in every golden test** (S): the cheapest version of clang-format's missing test; `GoldenFileSpec` can format each expected file once more. User value: high.
- **A resource guard that refuses rather than dies** (M): depth/size limits with a typed refusal, so a pathological config cannot take a pre-commit run down. User value: medium.
- **A documented "pin the version" line for CI** (S): `--version` plus "one version everywhere" covers it; clang-format users have no such story. User value: low, but free.

## The shared UX sources

**EditorConfig** ([spec.editorconfig.org](https://spec.editorconfig.org/)): a file named `.editorconfig` holds glob
sections; the search walks upward and stops at a file with `root = true`; within a file, later sections
win; keys are lowercased and `unset` reverses an earlier value. The standardized pairs are indentation
(`indent_style`, `indent_size`, `tab_width`), `end_of_line`, `charset`, `trim_trailing_whitespace` and
`insert_final_newline`; values are case-insensitive, and the spec's worked examples cover the
`indent_size`/`tab_width` interaction. For hocon-fmt the relevance is the **discovery rule**, not the
keys: `ConfigLookup` already implements the same shape (a config wins, `.git` stops the walk
otherwise), and no style key we own maps onto `.editorconfig` today. Black declines to read it
([#4487](https://github.com/psf/black/issues/4487)); Prettier reads it and lets `.prettierrc` override, and stops its search at
the project root unlike the spec ([configuration](https://raw.githubusercontent.com/prettier/prettier/main/docs/configuration.md));
clang-format users keep asking for it ([#50445](https://github.com/llvm/llvm-project/issues/50445)). If we ever offer a `.hocon-fmt.conf` key that
EditorConfig also standardizes, the spec is the reference for precedence and for `unset`.

**pre-commit** ([pre-commit.com](https://pre-commit.com/)): a hook is declared by `id`, `name`, `entry`, `language`
and file filters (`files`, `types`, `types_or`, `exclude`) evaluated with AND/OR rules; the hook "must
exit nonzero on failure or modify files"; `rev` pins the hook repository (tags, or a frozen commit
with `autoupdate --freeze`); pre-commit itself exits 1 for an expected error, 3 for an unexpected one
and 130 on interrupt; `pass_filenames`, `fail_fast` and stages (`pre-commit`, `pre-push`, `manual`, …)
shape how it runs; `language: coursier` is supported, and 3.0.0 added `repo: local` and
`additional_dependencies` for it — which matters because [ideas.md](../../ideas.md) names coursier as a
channel. hocon-fmt's `.pre-commit-hooks.yaml` and the wheel/npm hooks are exactly this contract: a
formatting hook that rewrites files fails the first commit and passes the next (the standard "first
run rewrites, then re-stage" dance), so our docs should recommend `--check` in CI and the fixing hook
locally. A hook whose *refusal* is not a failure is unusual here — pre-commit's model expects nonzero
to mean "fix me" — and worth one sentence in [usage.md](../../usage.md).

**gofmt** ([doc.go](https://raw.githubusercontent.com/golang/go/master/src/cmd/gofmt/doc.go)): the counter-example to all of
the above — no options at all, canonical output, `-l`/`-d`/`-w` and stdin. Its test suite is the
pattern for our fixed-point suite: `TestRewrite` formats `testdata/*.input` to `*.golden` and then
formats the golden again, checking idempotence ([gofmt_test.go](https://raw.githubusercontent.com/golang/go/master/src/cmd/gofmt/gofmt_test.go)),
and `TestCRLF` exists because line endings were a bug class there too. One warning from its docs sits
next to our write contract: `-w` restores the original file if the overwrite fails, and the doc admits
the restored file "may not have some of the original file attributes" — the same priority question we
answer with the identity-preserving staged write.

**scalafmt** ([configuration](https://scalameta.org/scalafmt/docs/configuration.html)): the closest neighbour, and the strictest
version story in the family — `.scalafmt.conf` must carry `version = 3.11.5` and `runner.dialect`,
and since v3.1.0 both are required; a differing version is resolved by fetching that exact release.
Users still hit version drift between editor and build ([#1318](https://github.com/scalameta/scalafmt/issues/1318) "Editor vs Build tool version syncing"
CLOSED 2018-11-14), and the formatter's own search-based rewrites oscillate:
[#5366](https://github.com/scalameta/scalafmt/issues/5366) "`rewrite.scala3.preset = \"common\"` oscillates forever (non-idempotent) on blank line inside brace-optional body"
CLOSED 2026-07-28, [#3448](https://github.com/scalameta/scalafmt/issues/3448) "newlines.source = keep … and fewerBraces cause incorrect and non-idempotent
formatting" CLOSED 2023-01-30, and the fixpoint option was asked for in 2017
([#1055](https://github.com/scalameta/scalafmt/issues/1055) "Add `formatCount = 1/2/fixpoint` option for guaranteed idempotent formatting" CLOSED 2017-09-27).
For hocon-fmt: version pinning is worth having *before* a 1.0 style change, and scalafmt's oscillation
is the strongest evidence that "run until stable" belongs in the tool as a refusal, not in an option.

**Five things to carry into the roadmap** (detail above, per tool): a generator-based idempotence
property for HOCON; a fixed-point assertion on every golden file; a resource/depth guard that refuses
instead of dying; a written style-stability statement plus `--required-version`; and a scheduled
corpus run over real configs. All five are cheap, and each is a bug class at least two of the four
projects found the hard way.
