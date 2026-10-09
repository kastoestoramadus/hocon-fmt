# Formatter research: data and markup formatters

The family: Prettier (JSON/YAML/Markdown/CSS), yamlfmt and yamllint, jq, Biome, dprint, the XML
formatters prettier-xml and xmllint, the protobuf formatters buf format and clang-format, and the
SQL formatters SQLFluff, sql-formatter and pgFormatter. Every issue and PR was returned by a
GitHub or GitLab API query run for this report on 2026-10-09, with the state and date that query
reported; every doc claim links the page it was read from. Anything else is marked *unverified*.

## Prettier — JSON, YAML, Markdown, CSS

**Tool**: Prettier (TypeScript/JS), <https://github.com/prettier/prettier>, 1.0.0 on 2017-05-28
(tag data), maintained by the Prettier team; the stance is "fewest options, one style", and
printWidth is deliberately a hint, not a limit.

**Design choices that matter to us**
- Opinionated: `printWidth` "is not the hard upper allowed line length limit… Prettier will make both shorter and longer lines"; strings and comments are never broken (<https://prettier.io/docs/options>).
- Comments: preserved by AST attachment in JS/CSS/YAML/Markdown; `.json` is parsed as strict JSON, so a comment there is a parse error (#2378, #5263) (<https://prettier.io/docs/rationale>).
- Blank lines: at most one is kept between statements; blank lines around comments are a recurring bug source (#9130, #6445).
- Ordering: never sorts by default; sorting is a plugin's job, so the core cannot displace a comment except through a plugin.
- Parse errors: refuse; a parse error aborts and writes nothing; `--check` exits 1 with the list of differing files and `--write` rewrites (<https://prettier.io/docs/cli>).
- Ignore pragmas: `// prettier-ignore` per node, `.prettierignore` per path; the YAML spellings were broken (#7805).
- Ranges: `--range-start`/`--range-end`, default 0/Infinity; a range snaps to "the start of the first line containing the selected statement" (<https://prettier.io/docs/options>).
- Config discovery: walks up for `.prettierrc`, honors `.editorconfig`, `--find-config-path` prints the winner, `--config`/`--no-config` override (<https://prettier.io/docs/cli>).
- stdin: `--stdin-filepath <path>` names the parser for piped input (<https://prettier.io/docs/cli>).

**Bug classes**
- **comment-loss** — JSON/JSONC comments rejected or dropped; `.json` is parsed as JSON, so `--write` refuses the file. [#5263](https://github.com/prettier/prettier/issues/5263) "[JSON] Trailing comments are not handled" (closed, 2018-10-14); [#2378](https://github.com/prettier/prettier/issues/2378) ".json which include comments" (closed, 2017-07-03). CSS analog: [#2127](https://github.com/prettier/prettier/issues/2127) "CSS: Comment gets eaten" (closed, 2017-06-13); [#2468](https://github.com/prettier/prettier/issues/2468) "CSS comment partially eaten" (closed, 2017-07-12).
- **comment-movement** — [#3178](https://github.com/prettier/prettier/issues/3178) "JSON comment at the top moved into object" (closed, 2017-11-07); [#4257](https://github.com/prettier/prettier/issues/4257) "markdown: multi-line comments keep moving to the right" (closed, 2018-04-04); [#19863](https://github.com/prettier/prettier/pull/19863) "fix(markdown): avoid inserting blank lines around HTML comments between list items" (closed, 2026-08-16).
- **idempotence** — a dedicated tracker: [#7055](https://github.com/prettier/prettier/issues/7055) "Idempotence testing: prettier(code) == prettier(prettier(code))" (closed, 2019-11-26); recent cases [#20052](https://github.com/prettier/prettier/issues/20052) "CSS: custom property with only `\!important\` as value is not idempotent — extra space added on every format" (closed, 2026-09-13); [#19644](https://github.com/prettier/prettier/issues/19644) "Markdown: non-idempotent indent drift (+4/run) for wrapped paragraph under a task-list item (regression in 3.9.0)" (closed, 2026-07-16).
- **semantic-change** — Markdown line breaks can change rendering: [#3344](https://github.com/prettier/prettier/issues/3344) "Markdown: line break behaviour creates new elements and changes meaning, makes Prettier non-idempotent" (closed, 2017-11-29); [#14360](https://github.com/prettier/prettier/issues/14360) "\"SyntaxError: Unexpected token\" when adding comments to markdown code" (closed, 2023-02-14).
- **line-width-layout** — comments are not reflowed and may exceed the width; YAML blank lines around them are the pain: [#10922](https://github.com/prettier/prettier/issues/10922) "Yaml remove new line before last comment" (closed, 2021-05-21); [#10926](https://github.com/prettier/prettier/pull/10926) "[YAML] Allow to set amount of spaces between comments and content" (closed, 2021-05-22).
- **whitespace-eol-encoding** — [#9130](https://github.com/prettier/prettier/issues/9130) "Unexpectedly doubles a blank line before a YAML comment (2.1.0 regression)" (closed, 2020-09-02), fixed by [#9143](https://github.com/prettier/prettier/pull/9143) "YAML: Fix printing doubles a blank line before a comment" (closed, 2020-09-04); [#18349](https://github.com/prettier/prettier/issues/18349) "Adding blank line after frontmatter breaks xml." (closed, 2025-11-27).
- **parse-error-handling** — JSONC and commented `package.json`: [#17107](https://github.com/prettier/prettier/issues/17107) "Currently, when looking for prettier config it will try to parse `package.json` and it will fail if it contains comments. Plee open an issue for it." (closed, 2025-02-13); YAML ignore being ignored: [#7805](https://github.com/prettier/prettier/issues/7805) "Prettier ignore comment is ignored on yaml files" (closed, 2020-03-20).
- **config-ignore** — [#8572](https://github.com/prettier/prettier/issues/8572) "Support prettier-ignore comment for specific option" (closed, 2020-06-16); [#7805](https://github.com/prettier/prettier/issues/7805) above.
- **embedded-language** — comments in a nested language drift: [#3889](https://github.com/prettier/prettier/issues/3889) "Comment in css (in markdown) identation keeps increasing" (closed, 2018-02-05); [#8690](https://github.com/prettier/prettier/issues/8690) "Intentional align of comments in YAML within Markdown is not respected" (closed, 2020-07-01); [#11202](https://github.com/prettier/prettier/issues/11202) "HTML comments followed by list in markdown code blocks adds heading 2" (closed, 2021-07-14).
- **crash** — [#4306](https://github.com/prettier/prettier/issues/4306) "Code blocks in markdown list items with two or more empty lines may crash parser" (closed, 2018-04-12); [#5219](https://github.com/prettier/prettier/issues/5219) "CSS in JS crashes with variable wrapped in quotes" (closed, 2018-10-09); [#9368](https://github.com/prettier/prettier/issues/9368) "Crash in forceNextEmptyLine" (closed, 2020-10-10).
- **check-exit-codes** — `--check` exits 1 on differences and prints the offenders, parse errors are hard errors (<https://prettier.io/docs/cli>); the ignored-YAML-ignore bug (#7805) makes `--check` disagree with what `--write` would do.

**Relevance to hocon-fmt**: high for comment-loss, comment-movement, idempotence and
whitespace-eol-encoding — HOCON's comment/blank-line semantics are exactly Prettier's YAML pain,
and Prettier's answer is keeping an AST, which we do not have. Medium for parse-error-handling
(their refuse-on-parse-error is ours) and config-ignore (we ship no pragma; a `.json` file must be
refused — our `OtherFormat`). Low for line-width (Prettier concedes comments are never reflowed)
and ordering (neither tool sorts by default).

**Probe**
- comment-loss: `a = 1 # trailing`, and a file holding only `# c`. Expected: comment kept, or `LostComment` — never a silent drop.
- comment-movement: `# why` above `b = 2`, and a comment after the last field. Expected: comment stays above `b`, or refusal (the known blank-line gap).
- idempotence: format twice; a file with CRLF, a BOM and two blank lines between fields. Expected: pass two equals pass one.
- whitespace-eol-encoding: same file with CRLF + BOM + trailing space. Expected: `\n` only, no BOM, byte-identical second run.
- parse-error-handling: `{ "a": 1 } // note` named `x.conf`. Expected: `NotHocon` refusal, file untouched, run exits 0.

**Development timeline**
- 1.0.0 (2017-05-28): the option-set freeze; the rationale page exists to defend it (<https://prettier.io/docs/rationale>).
- 1.9 (2017-12): `.json` comment handling tracked (#2378).
- 2.0.0 (2020-03-21): YAML/whitespace overhaul; source of the 2.1 blank-line regression (#9130).
- 3.0.0 (2023-07-05): trailing-comma default change.
- 3.6–3.9 (2025–2026): Markdown/YAML idempotence regressions filed steadily (#19644, #20052).
- **Ideas to migrate**: (S) expose our internal second pass as a `--debug-check`-style report; (M) a `--range` option — Prettier shows ranges are possible and that they snap to statements; (S) document why we have no ignore pragma.

## yamlfmt and yamllint

**Tool**: yamlfmt (Go), <https://github.com/google/yamlfmt>, v0.1.0 2022-08-22 (release list),
maintained by @braydonk, not officially supported by Google; three modes and a small option set.
yamllint (Python), <https://github.com/adrienverge/yamllint>, 0.1.0 on 2016-01-12 (PyPI), a
linter that never writes files — the ecosystem split lint from fix, and yamllint has no fix mode;
"yamllint-fix" does not exist, users pair it with yamlfmt.

**Design choices that matter to us**
- yamlfmt modes: format (default, rewrites), `-dry` (diff), `-lint` (diff + exit 1) (<https://raw.githubusercontent.com/google/yamlfmt/main/docs/command-usage.md>).
- yamlfmt stdin: `-`, `/dev/stdin` or `-in`, result to stdout (same page).
- yamlfmt config: `.yamlfmt` in the working directory, a flag path, or the global path (<https://raw.githubusercontent.com/google/yamlfmt/main/README.md>).
- yamlfmt comments: `pad_line_comments` sets spaces before a trailing comment, default one (release notes v0.9.0, 2023-04-02).
- yamlfmt anchors: `disallow_anchors` "will forcefully reject anchors" — a reject knob (release notes v0.6.0, 2022-11-01); comment handling rides on `yaml.v3` attachment, head/line/foot.
- yamllint config discovery: `.yamllint`/`.yamllint.yaml`/`.yamllint.yml` in the cwd or a parent (stopping at home/root), then `$YAMLLINT_CONFIG_FILE`, then XDG (<https://yamllint.readthedocs.io/en/stable/configuration.html>).
- yamllint exit codes: 1 for errors; with `--strict`, 0 none, 1 errors, 2 warnings only (same page).
- yamllint silence: per-line comments and path ignores; no autofix (same page).

**Bug classes**
- **comment-loss** — [#254](https://github.com/google/yamlfmt/issues/254) "yamlfmt removes comment from yaml with only a comment" (closed, 2025-06-10; the v0.17.1 release notes of 2025-06-19 explain the empty parse tree); [#258](https://github.com/google/yamlfmt/issues/258) "Difficult to comment out lines in a yaml file" (closed, 2025-06-18).
- **comment-movement** — [#255](https://github.com/google/yamlfmt/issues/255) "Incorrect comment movement for groups of array items" (closed); [#301](https://github.com/google/yamlfmt/issues/301) "Comment on multiline map key is moved to value" (closed).
- **idempotence** — [#289](https://github.com/google/yamlfmt/issues/289) "Not idempotent: adds extra newline after comment before document separator on repeated runs" (closed, 2025-11-25).
- **include-import-analog** (anchors/aliases) — [#242](https://github.com/google/yamlfmt/issues/242) "Issue when anchor is used as a key" (closed, 2025-03-13; v0.17.0 notes: `yaml.v3` emitted an invalid document); [#102](https://github.com/google/yamlfmt/issues/102) "Anchors formatted with unexpected merge tags" (closed, 2023-03-29); [#51](https://github.com/google/yamlfmt/issues/51) "Config option to reject anchors and aliases?" (closed, 2022-09-22).
- **whitespace-eol-encoding** — [#192](https://github.com/google/yamlfmt/issues/192) "New line issues with comments on Windows with CRLF EOL" (closed; v0.3.0 release notes, 2022-08-27: "Works with CRLF"); [#250](https://github.com/google/yamlfmt/issues/250) "Extra whitespace after !!merge <<: *anchor introduced in 0.17" (closed, 2025-05-26).
- **line-width-layout** — [#34](https://github.com/google/yamlfmt/issues/34) "yamlfmt insert blank line between commented line" (closed, 2022-08-26).
- **config-ignore** — [#104](https://github.com/google/yamlfmt/issues/104) "feat: support multiple spaces before line comments" (closed, 2023-04-01; shipped as `pad_line_comments`); [#51](https://github.com/google/yamlfmt/issues/51) above.
- **crash** — [#300](https://github.com/google/yamlfmt/issues/300) "frequent crashes" (closed, 2025-12-12).
- **other** (yamllint false positives on comments) — [#171](https://github.com/adrienverge/yamllint/issues/171) "Empty lines in comments not handled sensibly by require-starting-space" (closed, 2019-03-25); [#116](https://github.com/adrienverge/yamllint/issues/116) "comments rule with require-starting-space: true should special case shebang" (closed, 2018-05-27); [#361](https://github.com/adrienverge/yamllint/issues/361) "Rule 'comments' should ignore Cloud-Init \"#cloud-config\" similar to shebangs" (closed, 2021-02-23); [#603](https://github.com/adrienverge/yamllint/issues/603) "Wrong removal of empty lines before comments following empty or flow elements" (closed, 2023-10-20).
- **ordering** (yamllint anchors) — [#88](https://github.com/adrienverge/yamllint/issues/88) "anchor references seen as duplicate key" (closed, 2017-11-30), fixed by [#90](https://github.com/adrienverge/yamllint/pull/90) "88 anchor references seen as duplicate key" (closed, 2017-12-07); [#395](https://github.com/adrienverge/yamllint/issues/395) "yamllint allows referencing a non-existant anchor" (closed, 2021-07-20); [#786](https://github.com/adrienverge/yamllint/issues/786) "Remove rule \"anchors\" from default config file" (closed, 2025-10-11).

**Relevance to hocon-fmt**: high for comment-loss and comment-movement — "file of only comments
is erased" is the class our `LostComment` refusal exists for, and yamlfmt's fix (special-case the
input) is weaker than refusing. High for include-import-analog: an anchor key made the library
emit an invalid document and the answer was a config option to reject anchors — "refuse rather
than corrupt" arriving from the other side. Medium for idempotence (a comment gaining a newline
per pass is our `UnstableOutput`). Low for line-width.

**Probe**
- comment-loss: `# only a comment` in a file, and `o { # nothing }`. Expected: `LostComment` refusal, file untouched.
- include-import-analog: `o { include "x.conf" }` as the object's only content, then `o : 5`. Expected: `LostInclude` — already a suite entry; re-run as a release gate.
- comment-movement: a comment between two array elements. Expected: stays; otherwise refusal.
- idempotence: `a = [1, 2] # c` before the closing brace, twice. Expected: fixed point.

**Development timeline**
- v0.3.0 (2022-08-27): CRLF support via a hotfix shim for the upstream library.
- v0.6.0 (2022-11-01): `disallow_anchors`, a reject knob.
- v0.9.0 (2023-04-02): `pad_line_comments`, `regex_exclude`.
- v0.17.0/0.17.1 (2025-05/06): alias-as-key invalid output, comments-only file erasure.
- yamllint 1.36–1.38 (2025-03 → 2026-01): the anchors rule removed from defaults (2025-10-11).
- **Ideas to migrate**: (S) yamllint's 0/1/2 exit split (warnings vs errors) is worth comparing before release; (M) `regex_exclude` (skip a file whose content matches) is a cheap generated-file escape hatch we lack.

## jq

**Tool**: jq (C), <https://github.com/jqlang/jq>, the `jq-1.0` tag on 2015-08-08 (earlier 1.x
predates consistent tagging); not a formatter but the de-facto JSON printer (`jq .`), no
per-project config, fixed style (2-space indent; `--indent n` ≤ 7; `-S` sorts keys on request).

**Design choices that matter to us**
- Comments do not exist in JSON; jq cannot preserve them and the requests to accept them are closed (#402, #695). jq's own *program* language has `#` comments — a different surface (<https://jqlang.org/manual/>).
- Parse errors: refuse; `--seq` is the best-effort mode, skipping an unparsable JSON text with a warning (<https://jqlang.org/manual/>).
- Exit codes: 2 usage/system, 3 program-compile error, 0 ran; `-e` decides 0/1/4 from the last value's truthiness (<https://jqlang.org/manual/>).
- Key order preserved by default; `-S` sorts only when asked (<https://jqlang.org/manual/>).
- Numbers are printed in their original literal form when never mutated — a "keep the spelling" guarantee (<https://jqlang.org/manual/>).
- Modules: `include` and `import` with a search path — jq's HOCON-include analog, with its own crash history below (<https://jqlang.org/manual/>).

**Bug classes** (small tracker; the JSON printer has no comment or blank-line bugs by construction)
- **comment-loss** (structural) — [#402](https://github.com/jqlang/jq/issues/402) "Comments in Json" (closed, 2014-06-11); [#695](https://github.com/jqlang/jq/issues/695) "support comments in json" (closed, 2015-02-11); [#3041](https://github.com/jqlang/jq/issues/3041) "Ignore comments when reading JSONs" (closed, 2024-02-13).
- **include-import-analog** — [#3570](https://github.com/jqlang/jq/pull/3570) "Fix compile crash in transitive include chains" (closed, 2026-06-25); [#3497](https://github.com/jqlang/jq/pull/3497) "Fix crash when importing a module with errors twice" (closed, 2026-03-04).
- **crash** — [#1804](https://github.com/jqlang/jq/issues/1804) "Crash in jq_next calling jvp_utf8_decode_length on master 3c5b141" (closed, 2019-02-02); [#2533](https://github.com/jqlang/jq/issues/2533) "Crash on halt_error" (closed, 2023-01-30).
- **whitespace-eol-encoding** — [#2276](https://github.com/jqlang/jq/issues/2276) "Multiple consecutive newlines are causing memory error and crash with --raw-input" (closed, 2021-02-25); [#2447](https://github.com/jqlang/jq/issues/2447) "Cannot read HTML comments from arguments" (closed, 2022-06-26).
- **other** — [#2550](https://github.com/jqlang/jq/issues/2550) "organizing to release JQ 1.6.2 and JQ 1.7: Your suggestions/comments would be appreciated" (closed, 2023-03-07): the most-discussed item is the release cadence, not formatting.

**Relevance to hocon-fmt**: medium. The include analog matters: jq's transitive include chains
crashed the compiler for years and jq treats include failures as hard errors, supporting our
`LostInclude`/`MovedInclude` stance. The comment class is the mirror image of ours — a format
without comments has no comment bugs — and shows that "no comments" is JSON's design choice, not
a universal; jq refuses to guess rather than accepting comments. Low for idempotence.

**Probe**
- include-import-analog: `o { include "x.conf" }` then `o : 5` (already `LostInclude`); confirm exit code stays 0.
- comment-loss analog: `{ "a": 1 } // c` as `x.json` and as `x.conf`. Expected: `OtherFormat` and `NotHocon` respectively, no writes.

**Development timeline**
- 1.5 (2015-08), 1.6 (2018-11): the long gap after the original author's death.
- 1.7 (2023-09): revival under jqlang.
- 1.8.0 (2025-06) and 1.8.2 (2026-06): current line; include-chain and module crash fixes (#3497, #3570) landed in this window.
- **Ideas to migrate**: (S) `-S` is the family's only ordering feature that is safe because it is opt-in; remember that if sorting is ever proposed. (S) `--seq`'s warn-and-skip is the opposite of our rule and should stay that way.

## Biome

**Tool**: Biome (Rust), <https://github.com/biomejs/biome>, 1.0.0 on 2023-08-28 (tag data); fork
of Rome; formatter + linter + assist in one binary; opinionated defaults with a large option
surface; JSON/JSONC, CSS and HTML since v2.

**Design choices that matter to us**
- Options: indent style (tab default) and width, line width 80, quote style, per language (<https://biomejs.dev/formatter/>).
- Suppression syntax: `// biome-ignore format: reason` before a node, `// biome-ignore-all format: reason` at the top of a file; `// biome-ignore lint/...` for the linter (<https://biomejs.dev/formatter/>).
- Check mode: `biome format` reports, `--write` rewrites; `biome check` unifies format+lint; `--error-on-warnings` escalates (<https://biomejs.dev/reference/cli/>).
- stdin: `--stdin-file-path=PATH` picks settings by extension, writes processed code to stdout; virtual paths bypass includes/VCS checks (<https://biomejs.dev/reference/cli/>).
- Config discovery: `biome.json`/`biome.jsonc`, root-only for years; nested configs only partially supported (#6509, #7942).
- JSON: `package.json` formatted by default with a trailing-comma choice that conflicts with npm (#926, #4755).
- Sorting: `useSortedKeys` and import sorting are rules, i.e. opt-in, and carry the comment-displacement bugs below.

**Bug classes**
- **comment-movement** — [#3920](https://github.com/biomejs/biome/issues/3920) "🐛 Input `()` in a comment, the comment line moves strangely" (closed, 2024-09-14); [#3873](https://github.com/biomejs/biome/issues/3873) "📝 CSS Formatter inverts comma separated elements and comments" (closed, 2024-09-13); [#12058](https://github.com/biomejs/biome/pull/12058) "fix(useSortedKeys): break line after trailing line comment when sorting" (closed, 2026-10-01).
- **idempotence** — [#7912](https://github.com/biomejs/biome/issues/7912) "💅 Linter: `lint --write` is not idempotent on Astro frontmatter, while `lint` reports no issues" (closed, 2025-10-29); [#9970](https://github.com/biomejs/biome/issues/9970) "HTML `check --write` is not idempotent: embedded CSS/JS indentation grows on each pass when comments are present" (closed, 2026-04-13); [#1171](https://github.com/biomejs/biome/issues/1171) "📝 A commuted empty statement inside an arrow function leads to non-idempotent formatting" (closed, 2023-12-13).
- **semantic-change** — comments forcing invalid output: [#10363](https://github.com/biomejs/biome/pull/10363) "fix(format/html): fix case where comments cause invalid html" (closed, 2026-05-13); CSS comma inversion [#3873](https://github.com/biomejs/biome/issues/3873) above.
- **config-ignore** — [#9781](https://github.com/biomejs/biome/issues/9781) "🐛 [v2.4.10] format cli command tries (and fails) to format files with disabled formatting via biome ignore comment" (closed, 2026-04-02); [#6509](https://github.com/biomejs/biome/issues/6509) "🐛 Can't ignore nested biome.json configuration" (closed, 2025-06-23); [#7942](https://github.com/biomejs/biome/issues/7942) "🐛 Still can't ignore nested biome.json configuration" (closed, 2025-11-02).
- **check-exit-codes** — [#1634](https://github.com/biomejs/biome/issues/1634) "🐛 UTF-16 file throws error, but exit code is 0" (closed, 2024-01-22); [#3726](https://github.com/biomejs/biome/pull/3726) "fix(cli): don't emit diagnostics in stdin mode, and exit with error code" (closed, 2024-08-27).
- **parse-error-handling** — [#8451](https://github.com/biomejs/biome/issues/8451) "🐛 Parse error for JSONC files with comments in .vscode/settings.json and .cursor/settings.json" (closed, 2025-12-14); [#237](https://github.com/biomejs/biome/issues/237) "🐛 Tsconfig.json file always throw errors \"expected a property but instead found '}'\"" (closed, 2023-09-11).
- **JSON trailing commas** — [#926](https://github.com/biomejs/biome/issues/926) "📝 The formatter cannot remove trailing commas in JSON files, unlike Prettier" (closed, 2023-11-27); [#4755](https://github.com/biomejs/biome/issues/4755) "json.formatter.trailingCommas breaking package.json files" (closed, 2024-12-18).
- **crash** — [#8563](https://github.com/biomejs/biome/issues/8563) "🐛 Frequent crashes due to assertion failure: `start.raw <= end.raw`" (closed, 2025-12-23); [#1695](https://github.com/biomejs/biome/issues/1695) "💅 Consistent crash on malformed ternary" (closed, 2024-01-28); [#1950](https://github.com/biomejs/biome/issues/1950) "🐛  Biome formatter crashes on a valid typescript file with no further information" (closed, 2024-03-01).
- **ordering** — [#1563](https://github.com/biomejs/biome/issues/1563) "🐛 Import sorting breaks when a copyright comment is present" (closed, 2024-01-14); [#12058](https://github.com/biomejs/biome/pull/12058) above.
- **comment-loss** — [#8294](https://github.com/biomejs/biome/issues/8294) "📝 Astro comments aren't correctly recognized" (closed, 2025-11-27); [#6621](https://github.com/biomejs/biome/issues/6621) "💅 Squashing suppression comments causes false negatives" (closed, 2025-06-29).

**Relevance to hocon-fmt**: high for comment-movement and idempotence — embedded-language
indentation drift driven by comments (#9970) is the same shape as our sconfig attachment problem,
and the fix was in the printer, not a refusal; only a post-hoc second pass catches it, which is
our second-pass check. High for config-ignore: `biome-ignore` comments are themselves comments,
so they inherit the comment bugs. Medium for check-exit-codes (a UTF-16 error exiting 0 is the
silent pass we fixed in our CLI). Low for ordering.

**Probe**
- comment-movement: a comment above a nested field, with a sibling object after; format twice. Expected: same position both times.
- idempotence: a comment above a field inside an object under `simplify-nested-objects = false`, twice. Expected: fixed point.
- config-ignore analog: `__INCLUDE_0` written in prose inside a comment. Expected: `ReservedName` refusal, never silent restoration.

**Development timeline**
- 1.0.0 (2023-08-28): fork of Rome.
- 1.5 (2024-01): JSON/JSONC and `biome-ignore` stabilized; nested-config limitation visible (#6509).
- 2.0.0 (2025-06-17): per-language config sections, HTML/CSS expansion, `check` umbrella.
- 2.4–2.5 (2026-04 → 2026-08): idempotence reports peak (#9970, #9901).
- **Ideas to migrate**: (S) `--stdin-file-path` (settings by virtual path) matches our stdin story and is the cleanest contract in the family; (M) a `--reporter` set (`github`, `junit`) could serve CI users later; (S) quote Biome's "check must be idempotent" as prior art in our docs.

## dprint

**Tool**: dprint (Rust), <https://github.com/dprint/dprint>, earliest GitHub release 0.4.0 on
2020-06-16 (release list); pluggable — the CLI owns config, discovery, caching and the check
contract, while WASM language plugins own formatting; a small tracker.

**Design choices that matter to us**
- Config: `dprint.json`/`.jsonc`; nested configs are independent unless `"inherit": true`; `extends` accepts a path or URL (<https://dprint.dev/config/>).
- Overrides: plugin config per file pattern; includes/excludes and plugin routing stay top-level (<https://dprint.dev/config/>).
- Exit codes: 0 success, 1 general, 10 argument, 11 config, 12 plugin resolution, 13 no plugins, 14 no files (`--allow-no-files`), 20 `check` found unformatted files or `fmt --fail-on-change` (<https://dprint.dev/cli/>).
- Incremental by default: only files changed since the last run are formatted; `--incremental=false`, `--list-different`, `--json` (<https://dprint.dev/cli/>).
- stdin: `dprint fmt --stdin <path-or-ext>`; `--stdin-files` reads a newline-separated list (<https://dprint.dev/cli/>).
- Plugin boundary checks: the core errors when a plugin returns empty/whitespace-only output for non-whitespace text (#601, #750).
- Ignore comments are per-plugin, not core.

**Bug classes** (small tracker; few classes have two records)
- **crash** — [#669](https://github.com/dprint/dprint/issues/669) "Published npm package and vscode extension crash with \"thread 'tokio-runtime-worker' panicked at 'assertion failed: !result.is_null()'\"" (closed, 2023-05-02); [#877](https://github.com/dprint/dprint/issues/877) "Complex config + many CLI files = crash" (closed, 2024-07-02); [#834](https://github.com/dprint/dprint/issues/834) "plugin-prettier causes `dprint fmt` to crash on Apple M3" (closed, 2024-03-26).
- **whitespace-eol-encoding** — [#601](https://github.com/dprint/dprint/issues/601) "Error if plugin text is not all whitespace and the result is empty" (closed, 2022-12-17); [#750](https://github.com/dprint/dprint/issues/750) "Error when a plugin returns an empty string for non-whitespace text with char length over certain amount" (closed, 2023-09-26); [#1084](https://github.com/dprint/dprint/issues/1084) "[dockerfile] Indentation error with comments in command chaining &&" (closed, 2026-01-28).
- **comment-loss** — [#51](https://github.com/dprint/dprint/issues/51) "Missing comments when comment follows method in some scenarios" (closed, 2019-10-16); [#120](https://github.com/dprint/dprint/issues/120) "Fix comments inside empty braces, brackets, and parens" (closed, 2020-03-08).
- **idempotence** — [#422](https://github.com/dprint/dprint/issues/422) "Add runtime assertions that formatting is idempotent" (closed, 2021-09-30) — the only direct record, a request on the maintainer's own roadmap.
- **config-ignore** — [#213](https://github.com/dprint/dprint/issues/213) "Flag to automatically add an ignore file comment on files that error" (closed, 2020-05-19); [#20](https://github.com/dprint/dprint/issues/20) "Ability to suppress formatting of a node with a comment" (closed, 2019-08-17).

**Relevance to hocon-fmt**: medium. The exit-code contract teaches the most: dprint separates
"unformatted" (20) from config errors (11) and "no files" (14); users read those codes, and our
0/1/2 split keeps "refused" out of the failing set — dprint has no equivalent, a difference to
document. The plugin boundary checks (#601, #750) are our output check seen at an ABI. The small
tracker limits the rest.

**Probe**
- idempotence: a triple-quoted value that is whitespace-only. Expected: second pass equals the first; never an empty file.

**Development timeline**
- 0.4.0 (2020-06-16): earliest release in the list; plugins from the start.
- 0.13 (2021-09): the idempotence-assertion request (#422) opens.
- 0.45 (2023-05): documented exit-code table.
- 0.50 (2024-03/07): npm-package and large-argument crash fixes (#669, #877).
- 0.55 (2025-11): `--stdin-files`; 0.57 (2026): `check --json`.
- **Ideas to migrate**: (M) content-hash incremental cache (already in our ideas; dprint shows it is the adoption driver); (S) distinct exit codes for "refused" vs "unformatted" deserve comparison.

## XML: prettier-xml and xmllint

**Tool**: prettier-xml (JS/TS plugin), <https://github.com/prettier/plugin-xml>, 0.1.0 is the
CHANGELOG's earliest entry (date unverified); maintained by the Prettier plugin community;
whitespace-sensitive by design. xmllint (C, libxml2),
<https://gitlab.gnome.org/GNOME/libxml2>, part of libxml2 since 1999; `--format` reindents and
its maintainers have never claimed it is a source formatter.

**Design choices that matter to us**
- prettier-xml: `xmlWhitespaceSensitivity` is the central option; significant whitespace is detected per element; `xml:space="preserve"` overrides it (README, CHANGELOG 3.2.0, 2023-08-08).
- prettier-xml comments: initially not preserved at all (#2, #13, #189), now node-attached with the usual movement bugs.
- prettier-xml `xsl:text`: whitespace inside is always kept (CHANGELOG 3.3.0, 2024-02-09) — the "this text is verbatim" escape hatch.
- prettier-xml ordering: `xmlSortAttributesByKey` sorts attributes on request (CHANGELOG 3.2.2, 2023-10-27).
- xmllint `--format`: reindents; `--pretty INTEGER` sets the indent; `--noblanks` removes ignorable whitespace; `--nocdata` rewrites CDATA; comments are not reflowed (<https://gnome.pages.gitlab.gnome.org/libxml2/xmllint.html>).
- xmllint error model: reports and fails by default; `--recover` continues past well-formedness errors — the family's clearest best-effort switch (same page).
- xmllint includes: XInclude via `--xinclude`/`--noxincludenode`; `--path` governs lookup (same page).
- xmllint exit codes: the man page only mentions 11 for an empty XPath set; the rest is *unverified* (same page).

**Bug classes**
- **comment-loss** — [#2](https://github.com/prettier/plugin-xml/issues/2) "Preserve comments" (closed, 2019-11-13); [#13](https://github.com/prettier/plugin-xml/issues/13) "Commented elements are uncommented" (closed, 2019-11-15); [#189](https://github.com/prettier/plugin-xml/issues/189) "Comments are not preserved" (closed, 2021-06-03).
- **whitespace-eol-encoding** — [#138](https://github.com/prettier/plugin-xml/issues/138) "Some significant whitespace is inserted in \"xmlWhitespaceSensitivity: ignore\" mode" (closed, 2020-11-01); [#768](https://github.com/prettier/plugin-xml/issues/768) "Whitespace formatting isn't valid and idempotent with `ignore` sensitivity" (closed, 2024-02-16); [#748](https://github.com/prettier/plugin-xml/issues/748) "Preserve whitespace in empty `<xsl:text>` nodes" (closed, 2024-01-05).
- **semantic-change** — [#113](https://github.com/prettier/plugin-xml/issues/113) "Wrong whitespace added to csproj" (closed, 2020-08-19); [libxml2#380](https://gitlab.gnome.org/GNOME/libxml2/-/issues/380) "<![if !supportLists]>...<![endif]> turned into literal text instead of dropping  the tags (regression)" (closed, 2022-07-15).
- **line-width-layout** (xmllint) — [libxml2#609](https://gitlab.gnome.org/GNOME/libxml2/-/issues/609) "(innermost/terminating) indents in xmllint not enough" (closed, 2023-10-20); [libxml2#56](https://gitlab.gnome.org/GNOME/libxml2/-/issues/56) "Closing tag sometimes not indented (xmllint)" (closed, 2019-05-01); [libxml2#358](https://gitlab.gnome.org/GNOME/libxml2/-/issues/358) "xmllint --format inconsistently drops newlines" (closed, 2022-03-27); [libxml2#16](https://gitlab.gnome.org/GNOME/libxml2/-/issues/16) "xmllint failed to properly `--format` certain SVG image" (closed, 2018-08-15).
- **include-import-analog** (XInclude) — [libxml2#945](https://gitlab.gnome.org/GNOME/libxml2/-/issues/945) "xmllint 2.14: --path ignored and xi:include now errors without fallback" (closed, 2025-06-25); [libxml2#986](https://gitlab.gnome.org/GNOME/libxml2/-/issues/986) "xmllint --path behaviour change between 2.13.x and 2.14.x" (closed, 2025-09-16).
- **whitespace-eol-encoding** (xmllint) — [libxml2#628](https://gitlab.gnome.org/GNOME/libxml2/-/issues/628) "CRLF handling after a comment" (closed, 2023-11-25); [libxml2#151](https://gitlab.gnome.org/GNOME/libxml2/-/issues/151) "Incorrect line number counting when parsing comments with empty lines with CRLF endings" (closed, 2020-03-14); [libxml2#330](https://gitlab.gnome.org/GNOME/libxml2/-/issues/330) "Too many \"Double hyphen within comment\" errors for a single pass" (closed, 2022-01-27).
- **crash** — [#96](https://github.com/prettier/plugin-xml/issues/96) "Crashes while formatting NativeScript XML" (closed, 2020-07-03); [#864](https://github.com/prettier/plugin-xml/pull/864) "prevent crash in locStart/locEnd when node location is undefined" (closed, 2025-04-11); [libxml2#195](https://gitlab.gnome.org/GNOME/libxml2/-/issues/195) "Segfault in Xmllint at xmllint.c:3359" (closed, 2020-10-22); [libxml2#86](https://gitlab.gnome.org/GNOME/libxml2/-/issues/86) "stack overflow in libxml2-2.9.9  due to too much recursion" (closed, 2019-08-15).

**Relevance to hocon-fmt**: high for whitespace-eol-encoding and semantic-change — XML's
whitespace sensitivity is HOCON's triple-quoted-string problem in another dress: a formatter must
know which whitespace carries meaning, and prettier-xml needs an option plus escape hatches
(`xml:space`, `xsl:text`), which we lack because sconfig owns the round trip. High for
include-import-analog: xmllint 2.14 changed which files an include path resolved silently, the
exact class our masking and `MovedInclude` exist for. Medium for comment-loss. Low for
line-width.

**Probe**
- semantic-change: a triple-quoted value with leading/trailing whitespace and an embedded newline, under both separators. Expected: byte-identical string contents.
- include-import-analog: `include "a.conf"` where a sibling field defines the same key, so the include loses silently. Expected: the documented refusal list stays as documented.
- whitespace-eol-encoding: CRLF inside a triple-quoted string — sconfig normalizes to `\n`, changing the value. Expected: refusal or documented normalization; silence is not acceptable.

**Development timeline**
- 2019-11: first comment issues (#2) — comments lost from the start.
- 2020-11: whitespace-sensitivity bugs (#138).
- 2023-08/10: `xml:space="preserve"` honored; attribute sorting arrives — escape hatches after the bugs.
- 2024-02: `xsl:text` kept, whitespace-ignore made idempotent (#768) — whitespace class closed.
- libxml2 2.14 (2025-05): `--path`/XInclude behavior change (#945, #986).
- **Ideas to migrate**: (M) an explicit "verbatim region" concept (XML's `xml:space`) mapped to triple-quoted strings — we keep contents only by refusing; a documented promise is a feature; (S) `--pretty N`-style indent width is blocked by sconfig, note it.

## protobuf: buf format and clang-format

**Tool**: buf format (Go), <https://github.com/bufbuild/buf>, shipped in v1.3.0 on 2022-03-25
(release list); Buf's promise is whitespace-only edits with comments preserved. clang-format
(C++), <https://github.com/llvm/llvm-project>, in LLVM since 2007; formats Proto files too
(`Proto: .proto .protodevel`, <https://clang.llvm.org/docs/ClangFormat.html>) and is the other
formatter proto users use.

**Design choices that matter to us**
- buf style: no options beyond the config; `-w/--write` rewrites, `-d/--diff` prints, `--exit-code` fails when files were not already formatted (<https://buf.build/docs/reference/cli/buf/format>).
- buf output: stdout by default, `-o/--output` selects dir/protofile, `--path` limits scope (same page).
- buf config: `--config` takes a `buf.yaml` file or data (same page).
- buf has no documented stdin mode (same page); CI use is `-w`/`--exit-code`.
- buf lint ignores: `// buf:lint:ignore RULE` is separate from formatting and had years of bugs (#63, #3048, #4046) — semantically load-bearing comments are fragile.
- clang-format discovery: `-style=file` searches parents for `.clang-format`; `--assume-filename` for stdin; `--fallback-style` covers a miss (<https://clang.llvm.org/docs/ClangFormat.html>).
- clang-format ranges: `--lines=start:end` or `--offset/--length`, multiple ranges, single input file; `--dry-run`/`-n` reports, `-i` writes (same page).
- clang-format switches: `DisableFormat` per language; `// clang-format off` … `on` regions; `ReflowComments`, `CommentPragmas`, `SortIncludes` (<https://clang.llvm.org/docs/ClangFormatStyleOptions.html>).

**Bug classes**
- **comment-movement** — [llvm#46933](https://github.com/llvm/llvm-project/issues/46933) "clang-format-11: regression in aligned comments" (closed, 2020-09-20); [llvm#59635](https://github.com/llvm/llvm-project/issues/59635) "clang-format-16 `AlignTrailingComments: Kind: Leave` moves comments #2" (closed, 2022-12-21); [llvm#67906](https://github.com/llvm/llvm-project/issues/67906) "clang-format-18 Comments alligned across scopes" (closed, 2023-10-01); [llvm#208324](https://github.com/llvm/llvm-project/pull/208324) "[clang-format] Fix OverEmptyLines aligning trailing comments across block boundaries" (closed, 2026-07-08).
- **comment-loss** (integrity/misindent) — [llvm#52649](https://github.com/llvm/llvm-project/issues/52649) "clang-format breaks comment integrity with SpacesInLineCommentPrefix" (closed, 2021-12-13); [llvm#21069](https://github.com/llvm/llvm-project/issues/21069) "clang-format c comment in preprocessor macro" (closed, 2014-08-18); [llvm#32477](https://github.com/llvm/llvm-project/issues/32477) "clang-format resets comment indentation before ifdefs" (closed, 2017-05-22); [llvm#55795](https://github.com/llvm/llvm-project/issues/55795) "[clang-format] Misindented comments inside preprocessor directives." (closed, 2022-05-31).
- **idempotence** — [llvm#51808](https://github.com/llvm/llvm-project/issues/51808) "clang-format not idempotent with long comment containing tabs" (closed, 2021-11-10); buf [#4620](https://github.com/bufbuild/buf/issues/4620) "`buf format` silently deletes a comment that is separated from the following element by a blank line inside an option aggregate (not idempotent)" (closed, 2026-07-19); [#4650](https://github.com/bufbuild/buf/issues/4650) "buf format v1.71+ regressions: non-idempotent inline block comments and stripped interior indentation" (closed, 2026-08-21); [#3148](https://github.com/bufbuild/buf/issues/3148) "buf format: Unstable results with comment after a value in an array in an option" (closed, 2024-07-10).
- **comment-loss** (buf) — [#3322](https://github.com/bufbuild/buf/issues/3322) "Buf format deletes inline comments in some cases" (closed, 2024-09-15); [#3423](https://github.com/bufbuild/buf/issues/3423) "`buf beta lsp` formatting duplicates leading comments in file" (closed, 2024-10-28); [#4620](https://github.com/bufbuild/buf/issues/4620) above.
- **whitespace-eol-encoding** — buf [#1924](https://github.com/bufbuild/buf/issues/1924) "`buf format` preserves indentation on blank lines inside block comments" (closed, 2023-03-16); [#3005](https://github.com/bufbuild/buf/issues/3005) "Buf format reported to add whitespace within commented-out lines" (closed, 2024-05-21); [#1839](https://github.com/bufbuild/buf/issues/1839) "buf format: comment whitespace is still a little wonky" (closed, 2023-02-15); [#1062](https://github.com/bufbuild/buf/issues/1062) "Format with trailing comments alignment" (closed, 2022-04-11).
- **broken output** — [#1370](https://github.com/bufbuild/buf/issues/1370) "latest docker version buf formats text into invalid protobuf" (closed, 2022-08-31); [#4593](https://github.com/bufbuild/buf/issues/4593) "buf format produces a syntax error in v1.171.0" (closed, 2026-06-24); [#4035](https://github.com/bufbuild/buf/issues/4035) "Formatter produces newline at beginning of file if no syntax and declarations come before package" (closed, 2025-10-05); [#3438](https://github.com/bufbuild/buf/issues/3438) "Formatting with the LSP removes invalid syntax instead of erroring" (closed, 2024-10-31).
- **partial-range** — [llvm#56352](https://github.com/llvm/llvm-project/issues/56352) "clang-format crash with --lines and `CompactNamespaces: true NamespaceIndentation: All`" (closed, 2022-07-02); ranges are documented as single-input only (<https://clang.llvm.org/docs/ClangFormat.html>).
- **ordering** — [llvm#177326](https://github.com/llvm/llvm-project/pull/177326) "[clang-format]  Ignore imports in comments for Java import sorting" (closed, 2026-01-22); `SortIncludes` reorders includes (<https://clang.llvm.org/docs/ClangFormatStyleOptions.html>).
- **crash** — [llvm#50007](https://github.com/llvm/llvm-project/issues/50007) "[clang-format] Crash with Whitesmiths and labels in top-level block" (closed, 2021-06-10); [llvm#51277](https://github.com/llvm/llvm-project/issues/51277) "clang-format-13 crashes with AlignArrayOfStructures" (closed, 2021-09-22); [llvm#55493](https://github.com/llvm/llvm-project/issues/55493) "[clang-format] `AlignArrayOfStructures` crashes with trailing comment" (closed, 2022-05-16); [llvm#33293](https://github.com/llvm/llvm-project/issues/33293) "Clang-format crashes on file with UCS-2 LE BOM encoding   Assertion failed: getClient() && \"DiagnosticClient not set!\"" (closed, 2017-07-26); buf [#4028](https://github.com/bufbuild/buf/issues/4028) "Segfault when formatting file that is missing field order specifiers" (closed, 2025-09-24).
- **performance** — buf [#3593](https://github.com/bufbuild/buf/issues/3593) "Format very slow when `--path` not used; confusing UX for formatting multiple files" (closed, 2025-01-15).
- **check-exit-codes** — buf [#1230](https://github.com/bufbuild/buf/issues/1230) "buf format and an buf lint behave inconsistently when no *.proto files discovered." (closed, 2022-06-24); `--exit-code` is the documented contract (<https://buf.build/docs/reference/cli/buf/format>).
- **config-ignore** — [#63](https://github.com/bufbuild/buf/issues/63) "Add configuration option to enable inline comment-driven ignores" (closed, 2020-05-21); [#3048](https://github.com/bufbuild/buf/issues/3048) "`// buf:lint:ignore PROTOVALIDATE` comment ignore does not work" (closed, 2024-06-05); [#4046](https://github.com/bufbuild/buf/issues/4046) "buf lint comment ignores do not work" (closed, 2025-10-10).

**Relevance to hocon-fmt**: high for broken output and idempotence — buf format is the closest
sibling to our promise (comments preserved, only whitespace changed) and still shipped releases
that deleted comments, produced invalid protobuf and re-ran differently; a second-pass check and
a comment audit are not paranoia. High for comment-loss: #4620 deletes a comment separated by a
blank line inside an option aggregate — our exact blank-line-detaches pattern, but buf fixes it
in the formatter, which we cannot do inside sconfig. Medium for partial-range and config-ignore.
Low for crash.

**Probe**
- broken output: format every example file under both separator options and diff the second pass against the first. Expected: fixed points only.
- comment-loss: `a = 1`, blank line, `# c`, `b = 2` (the #4620 pattern). Expected: `LostComment` today; the test documents the gap.
- idempotence: `o = { a = 1 # c\n }`, twice. Expected: identical.

**Development timeline**
- buf v1.3.0 (2022-03-25): `buf format` introduced.
- v1.29.0 (2024-01) / v1.42.0 (2024-09): `Any`-syntax mangling and trailing-comment fixes; `-w` stops touching unchanged files.
- v1.59.0 (2025-10): whitespace before the first header fixed (#4035).
- v1.68–1.73 (2026-04 → 2026-09): edition 2024 support, then non-idempotence/comment regressions (#4620, #4650).
- clang-format: `ReflowComments` since 3.8 (2016); `ReflowComments: IndentOnly` proposed 2024-06 ([llvm#96804](https://github.com/llvm/llvm-project/pull/96804)) after reflow broke Doxygen blocks; `AlignArrayOfStructures` crash fixes 2021–2023.
- **Ideas to migrate**: (S) `-d/--diff` next to `--check` (already in our ideas); (S) `CommentPragmas` — a regex naming comments never to touch — is a cheaper ignore than pragma syntax and could fit `.hocon-fmt.conf`; (M) `--exit-code`-style opt-in failure on check shows users want the non-zero code.

## SQL: SQLFluff, sql-formatter, pgFormatter

**Tool**: SQLFluff (Python), <https://github.com/sqlfluff/sqlfluff>, 0.0.1 on 2018-11-07 and 1.0.0
on 2022-06-17 (PyPI); linter + fixer with templating and per-dialect parsers. sql-formatter
(TypeScript), <https://github.com/sql-formatter-org/sql-formatter>, formatter only, 20+ dialects.
pgFormatter (Perl), <https://github.com/darold/pgFormatter>, v1.0 on 2012-12-22, the oldest tool
here, a beautifier with an rc file and no linter.

**Design choices that matter to us**
- SQLFluff exit codes: 0 no issues, 1 issues found (including a file that could not be parsed), 2 an error — the cleanest "unparsable is not a crash" contract here (<https://raw.githubusercontent.com/sqlfluff/sqlfluff/main/docsv/usage/cli.md>).
- SQLFluff config discovery: `setup.cfg`, `tox.ini`, `pep8.ini`, `.sqlfluff`, `pyproject.toml`, merged later-wins (<https://raw.githubusercontent.com/sqlfluff/sqlfluff/main/docsv/configuration/index.md>).
- SQLFluff templating: Jinja/dbt substitution before parsing, with placeholders — an explicit embedded-language boundary (same docs tree).
- SQLFluff ignores: `-- noqa` inline, block-comment noqa, `--disable-noqa`; `.sqlfluffignore` for paths (#4891, #5133).
- SQLFluff fix scope: only enabled rules change layout; L016 (line length) can skip comment lines (#299, #2554) — comments are admitted as not-code-to-reflow.
- sql-formatter CLI: stdin→stdout by default, `-o` output, `--fix` in place, `.sql-formatter.json` in the cwd or a parent, `--config`/`-l`; no `--check` mode (<https://github.com/sql-formatter-org/sql-formatter#readme>).
- sql-formatter disable comments: `/* sql-formatter-disable */` … `enable` documented in the README (same page).
- pgFormatter: `-i/--inplace`, stdin→stdout, `.pg_format` rc file (`-X` skips), `--config`, `-C/--wrap-comment`, `--vertical-align` (<https://raw.githubusercontent.com/darold/pgFormatter/master/README>, <https://raw.githubusercontent.com/darold/pgFormatter/master/ChangeLog>).

**Bug classes**
- **comment-loss** — SQLFluff [#128](https://github.com/sqlfluff/sqlfluff/issues/128) "Bug: Some comments were deleted when I ran \"sqlfluff fix --rules L016\"" (closed, 2020-01-16); [#3157](https://github.com/sqlfluff/sqlfluff/issues/3157) "Commented dash character converted to non utf-8 character" (closed, 2022-04-22); sql-formatter [#50](https://github.com/sql-formatter-org/sql-formatter/issues/50) "Formatting comments, new lines are not kept" (closed, 2018-09-23); [#899](https://github.com/sql-formatter-org/sql-formatter/issues/899) "Preserve comment lines and leading commas in SQL formatting" (closed, 2025-09-04); pgFormatter [#130](https://github.com/darold/pgFormatter/issues/130) "Not all comments are removed" (closed, 2019-08-06); [#80](https://github.com/darold/pgFormatter/issues/80) "Not all comments are removed" (closed, 2018-12-12).
- **comment-movement** — sql-formatter [#365](https://github.com/sql-formatter-org/sql-formatter/issues/365) "[FORMATTING] Comments move their lines after formatting" (closed, 2022-08-01); [#481](https://github.com/sql-formatter-org/sql-formatter/issues/481) "First comment in file gets indented by single space" (closed, 2022-09-30); [#236](https://github.com/sql-formatter-org/sql-formatter/issues/236) "tabulateAlias true doesn't work after comment in SELECT" (closed, 2022-06-13); [#558](https://github.com/sql-formatter-org/sql-formatter/issues/558) "[FORMATTING] Extra tabs added to multiline comments " (closed, 2023-02-03); pgFormatter [#193](https://github.com/darold/pgFormatter/issues/193) "Comment integrity is compromised and indentation gets reset after comment" (closed, 2020-05-17); [#188](https://github.com/darold/pgFormatter/issues/188) "Inserting line comment before comma breaks indentation in CTE" (closed, 2020-05-12); [#57](https://github.com/darold/pgFormatter/issues/57) "Block comments indentation" (closed, 2018-09-04).
- **semantic-change** — SQLFluff [#6801](https://github.com/sqlfluff/sqlfluff/issues/6801) "Formatting `AND` followed by blank/comment line with `binary_operation` `spacing_after=\"any\"` changes meaning of the code" (closed, 2025-04-10); pgFormatter [#194](https://github.com/darold/pgFormatter/issues/194) "Multi-line text (not a comment) starting with `---` gets corrupted upon formatting" (closed, 2020-05-18); [#76](https://github.com/darold/pgFormatter/issues/76) "comments with semi colons become statements" (closed, 2018-11-30); [#338](https://github.com/darold/pgFormatter/issues/338) "Single quotes inside /**/ comments break formatting" (closed, 2024-04-24); sql-formatter [#44](https://github.com/sql-formatter-org/sql-formatter/issues/44) "Handling comments inside SQL string" (closed, 2018-05-08).
- **crash** — SQLFluff [#398](https://github.com/sqlfluff/sqlfluff/issues/398) "parser crashes with comment at beginning of file" (closed, 2020-07-25); [#2994](https://github.com/sqlfluff/sqlfluff/issues/2994) "Crash while parsing dbt SQL with macro" (closed, 2022-04-04); [#6456](https://github.com/sqlfluff/sqlfluff/issues/6456) "Crash when running sqlfluff fix on query that contains JOIN with subquery" (closed, 2024-11-14); sql-formatter [#500](https://github.com/sql-formatter-org/sql-formatter/issues/500) "Crash with BETWEEN inside CASE expression" (closed, 2022-10-13); [#810](https://github.com/sql-formatter-org/sql-formatter/issues/810) "Using $action in tsql merge causes crash" (closed, 2024-12-20); pgFormatter [#302](https://github.com/darold/pgFormatter/issues/302) "Crash on uncaught exception -- use of uninitialized value" (closed, 2023-01-12); [#105](https://github.com/darold/pgFormatter/issues/105) "USING clause crash" (closed, 2019-05-01); [#242](https://github.com/darold/pgFormatter/issues/242) "crashes with \"Unmatched ) in regex\"" (closed, 2021-04-08).
- **idempotence** — pgFormatter [#321](https://github.com/darold/pgFormatter/issues/321) "Formatting is not idempotent" (closed, 2023-07-05); SQLFluff [#8068](https://github.com/sqlfluff/sqlfluff/pull/8068) "Fix LT07 non-idempotent fix for CTE brackets that become multi-line" (closed, 2026-07-04); sql-formatter [#952](https://github.com/sql-formatter-org/sql-formatter/issues/952) "fix: keep leading block comment of clause item on its own line" (closed, 2026-06-03; the release notes call it "Fix block-comment placement idempotency issue (#952)", <https://github.com/sql-formatter-org/sql-formatter/releases>).
- **config-ignore** (noqa) — SQLFluff [#4891](https://github.com/sqlfluff/sqlfluff/issues/4891) "-- noqa ignored in multiline comment" (closed, 2023-05-30); [#1985](https://github.com/sqlfluff/sqlfluff/pull/1985) "Fix issue with inline ignores not respecting comment lines" (closed, 2021-11-26); [#5133](https://github.com/sqlfluff/sqlfluff/pull/5133) "Follow noqa in block comments" (closed, 2023-08-26); [#2493](https://github.com/sqlfluff/sqlfluff/issues/2493) "Add `inline_comment_prefixes=\"#\"` to configparser" (closed, 2022-01-27).
- **parse-error-handling** — SQLFluff [#5754](https://github.com/sqlfluff/sqlfluff/issues/5754) "mysql  sql('CREATE DATABASE IF NOT EXISTS xxx CHARACTER SET \"utf8mb4\" COLLATE \"utf8mb4_bin\" COMMENT \"xxx\";') but parse,lint   can't understand right  and give error  thart  Found unparsable section: 'CHARACTER SET'" (closed, 2024-04-07); [#224](https://github.com/sqlfluff/sqlfluff/issues/224) "\"sqlfluff lint\" crashes if there are no files to process" (closed, 2020-04-09); sql-formatter refuses an unclosed `/*` (release notes, <https://github.com/sql-formatter-org/sql-formatter/releases>).
- **line-width-layout** — SQLFluff [#299](https://github.com/sqlfluff/sqlfluff/issues/299) "comment length greater than max_line_length results in violation" (closed, 2020-05-07); [#2554](https://github.com/sqlfluff/sqlfluff/issues/2554) "Update L016 to optionally ignore long COMMENT lines as well" (closed, 2022-02-03).
- **ordering** — SQLFluff [#8296](https://github.com/sqlfluff/sqlfluff/pull/8296) "fix(ST06): don't reorder select targets when it would displace comments" (closed, 2026-08-08); pgFormatter `--vertical-align` aligns rather than reorders (<https://raw.githubusercontent.com/darold/pgFormatter/master/ChangeLog>).
- **embedded-language** — SQLFluff [#3647](https://github.com/sqlfluff/sqlfluff/issues/3647) "Support for linting structured comments that contain SQL" (closed, 2022-07-21); [#2493](https://github.com/sqlfluff/sqlfluff/issues/2493) above.

**Relevance to hocon-fmt**: high for comment-loss and comment-movement — the densest evidence in
the family that a formatter over a language with line and block comments breaks on comment
placement, and every tool's fix list is longer than its feature list. High for idempotence:
pgFormatter's report and SQLFluff's LT07 fix are our `UnstableOutput` check validated from three
codebases. High for parse-error-handling: SQLFluff's exit-code table ("unparsable file = exit 1,
not a crash") is worth citing in our docs; sql-formatter refusing an unclosed block comment
matches our stance. Medium for config-ignore (noqa comments ignored inside multiline comments is
our attachment problem again). Low for line-width and ordering.

**Probe**
- comment-loss: a comment as the only content of an object, and a comment between two fields; twice. Expected: `LostComment` or a byte-identical second pass.
- comment-movement: `a = [ 1 # c\n ]` and a comment before a closing brace. Expected: no displacement, or refusal.
- idempotence: `a.b.c = 1` with comments before and after, a `+=` append and a nested object; twice. Expected: pass two equals pass one.
- parse-error-handling: `a = "unterminated` — expected `NotHocon`, run exits 0. Note that the "unparsable section" concept has no HOCON analog: we refuse the file instead.

**Development timeline**
- pgFormatter: continuous since 2012; v5.9 (2025-12-21) → v5.11 (2026-09-04) are all user-reported fixes, including comment spacing, "lines involuntary turned to comment" and dollar-quoted strings.
- sql-formatter: comments attached to AST nodes 2021, nested block comments 2021, disable comments 2022, first-comment indentation 2022 (#481), block-comment idempotency 2026 (#952).
- SQLFluff: 1.0.0 (2022-06-17) with dbt/Jinja; templating then dominates the tracker; ST06 comment-displacement fix 2026-08.
- **Ideas to migrate**: (M) mirror SQLFluff's exit-code wording in our docs (0/1/2, "unparsable is 1") while keeping refusals non-failing; (S) record "unparsable section" as a non-goal — a HOCON object either parses or not; (M) sql-formatter's `--fix`-only workflow is what our check mode replaces, and its issue list is the argument for a check mode: every comment-loss report arrives after a `--fix`.
