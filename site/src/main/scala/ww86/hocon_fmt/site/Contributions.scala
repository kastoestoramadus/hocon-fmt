package ww86.hocon_fmt.site

import ww86.hocon_fmt.site.Library.LightbendConfig
import ww86.hocon_fmt.site.Library.Sconfig

/** The snapshot of the author's pull requests against the libraries this formatter depends on,
  * as read from GitHub with `gh` on 2026-10-07. It ships in the page, so the list renders
  * offline, from file://, and when the API is out of reach; the live refresh only updates it.
  *
  * States were derived from the searches plus the release histories: sconfig v2.0.0 was published
  * 2026-08-24 and lightbend/config v1.4.9 on 2026-06-03, so anything merged after those dates is
  * in no release yet. A pull request counts as fixed only in that released state.
  */
object Contributions:

  /** The day `gh search prs --author kastoestoramadus` last fed this file. */
  val readOn = "2026-10-07"

  def byNumber(library: Library, number: Int): Option[Contribution] =
    all.find(c => c.library == library && c.number == number)

  // --- ekrich/sconfig ---------------------------------------------------------------------------

  private val sconfig = List(
    Contribution(
      Sconfig, 438, "Making rendering useful for formatting files.",
      Theme.FormatterProposal, PrState.Closed,
      "The first proposal to make sconfig render for formatting files — origin order, indentation, " +
        "the assign sign; closed unmerged, and sconfig later grew `ConfigFormatOptions` with most " +
        "of it, which hocon-fmt builds on."
    ),
    Contribution(
      Sconfig, 598, "Make the renderer's output parseable and a fixed point",
      Theme.UnresolvedMerges, PrState.MergedUnreleased,
      "Where a key is defined twice — `a : [1]` then `a += 2`, or an object concatenated onto a " +
        "substitution — the renderer produced text it cannot read back and output that grew on " +
        "every pass; it now renders what parses back and is a fixed point."
    ),
    Contribution(
      Sconfig, 599,
      "Let a partially resolved value that cannot become an object hide the merge stack below it",
      Theme.UnresolvedMerges, PrState.Open,
      "With unresolved substitutions allowed, repeated appends stack merges that render as text " +
        "that cannot be parsed back; a value that cannot become an object now hides the merge " +
        "stack below it."
    ),
    Contribution(
      Sconfig, 600, "Write merge stack entries with the render options' key and separator",
      Theme.UnresolvedMerges, PrState.Open,
      "Unresolved-merge entries were written with a hard-coded quoted key and ` : `, ignoring the " +
        "render options, so one output mixed `\"a\" : 1` with `sib = 0`."
    ),
    Contribution(
      Sconfig, 590, "Port lightbend/config#832 and #841: two rendering round-trip fixes (#2)",
      Theme.RendererRoundTrip, PrState.MergedUnreleased,
      "An object as the first entry of a root-level array lost its braces in non-JSON output, and " +
        "an unquoted string inside a value concatenation was re-quoted, which does not parse."
    ),
    Contribution(
      Sconfig, 613, "Drop Option and Vector from the renderer code",
      Theme.RendererRoundTrip, PrState.MergedUnreleased,
      "Renderer internals: direct null checks and an array of chars replace the `Option` and " +
        "`Vector` wrappers on the render path; no behaviour change."
    ),
    Contribution(
      Sconfig, 497, "[496] bugfix rendering for multipaths in arrays",
      Theme.RendererRoundTrip, PrState.Released,
      "Fixes rendering a dotted-path entry inside an array (issue #496), which came out unreadable."
    ),
    Contribution(
      Sconfig, 522, "Fix of broken single path optimization on NonRoot.",
      Theme.RendererRoundTrip, PrState.Released,
      "The optimisation that joins a single-field object into a dotted path broke rendering on " +
        "nested objects; the join now holds back when comments are in the way."
    ),
    Contribution(
      Sconfig, 523, "Fix and refine previous rendering fix",
      Theme.RendererRoundTrip, PrState.Released,
      "Refines the preceding rendering fix, with tests pinning the default rendering."
    ),
    Contribution(
      Sconfig, 516, "Broken String concat at rendering bugfix",
      Theme.RendererRoundTrip, PrState.Released,
      "A string concatenation whose unquoted piece is a single space was re-quoted, which does " +
        "not parse back; the space is now emitted verbatim (generalised by #590)."
    ),
    Contribution(
      Sconfig, 466, "[ISSUE-465] Bugfix for unquoted Strings, exception with '.' kept",
      Theme.RendererRoundTrip, PrState.Released,
      "Keys were quoted when they did not need it; an unquoted key, even one containing a dot, " +
        "keeps its spelling now."
    ),
    Contribution(
      Sconfig, 467, "Additional space after assign - rendering improvement",
      Theme.RendererRoundTrip, PrState.Released,
      "A space after the assign sign, so `a = 1` renders instead of `a =1`."
    ),
    Contribution(
      Sconfig, 468, "Compact single nested object entry - rendering improvement",
      Theme.RendererRoundTrip, PrState.Released,
      "Adds the option that renders a single-field nested object as one dotted-path entry — the " +
        "flattening the formatter asks for."
    ),
    Contribution(
      Sconfig, 601, "Attach the comment above a `+=` field to the field, not also to its element",
      Theme.Comments, PrState.Open,
      "The comment above `a += 2` was rendered twice, once on the field and once on its synthetic " +
        "list element."
    ),
    Contribution(
      Sconfig, 515, "fix of deleted comments when compacted multipath is turned on",
      Theme.Comments, PrState.Released,
      "With the compact dotted-path rendering on, comments were dropped; they are kept now."
    ),
    Contribution(
      Sconfig, 525, "when comments = no multipath rendering optimization",
      Theme.Comments, PrState.Released,
      "Comments on the parent or the leaf now switch the dotted-path rendering off, which would " +
        "otherwise move them."
    ),
    Contribution(
      Sconfig, 472, "[BUGFIX] surplus spaces in array comments",
      Theme.Comments, PrState.Released,
      "Every round trip added two spaces before comments in arrays, so a formatter built on the " +
        "renderer never settled; the spacing is stable now."
    ),
    Contribution(
      Sconfig, 595, "Port lightbend/config#839+#846: don't evaluate substitutions hidden by values from resolved objects",
      Theme.SubstitutionsAndConcatenations, PrState.MergedUnreleased,
      "Per the specification a substitution hidden by a value it cannot merge with is never " +
        "evaluated; that now also holds when the hiding value itself arrives through a substitution."
    ),
    Contribution(
      Sconfig, 596, "Port lightbend/config#725: route replaceChild through a held ConfigConcatenation's pieces",
      Theme.SubstitutionsAndConcatenations, PrState.MergedUnreleased,
      "Replacing a child inside a value concatenation now routes through the concatenation's own " +
        "pieces, which fixes wrong results for several configs (issues #725, #647, #356)."
    ),
    Contribution(
      Sconfig, 620, "Keep object merge history at the source key",
      Theme.SubstitutionsAndConcatenations, PrState.Open,
      "A key set to `null` and later to an object leaked the `null` into every key that " +
        "substituted it, so the receiving key lost its own earlier values (ports #864)."
    ),
    Contribution(
      Sconfig, 621, "Fix resolveWith() for overloaded keys with delayed merge",
      Theme.SubstitutionsAndConcatenations, PrState.Open,
      "`resolveWith` threw `BugOrBroken` for a key defined twice whose later definition is a " +
        "substitution; it resolves now (ports #856)."
    ),
    Contribution(
      Sconfig, 636, "Limit parser nesting and wrap resolver stack overflow",
      Theme.ParserLimitsAndNumbers, PrState.Open,
      "Deeply nested objects or arrays and long `+=` chains escaped as `StackOverflowError`; " +
        "parsing now rejects more than 100 levels and a resolver overflow comes back as a parse error."
    ),
    Contribution(
      Sconfig, 635, "Reject out-of-range long conversions and preserve exact boundaries",
      Theme.ParserLimitsAndNumbers, PrState.Open,
      "`getLong` silently clamped out-of-range numbers to `Long.MaxValue` or `Long.MinValue`; it " +
        "now throws, and exact boundary values still read correctly."
    ),
    Contribution(
      Sconfig, 634, "Render non-finite doubles as quoted strings",
      Theme.ParserLimitsAndNumbers, PrState.Open,
      "`Infinity` and `NaN` rendered as bare tokens, which is not valid JSON; they render as " +
        "quoted strings that read back."
    ),
    Contribution(
      Sconfig, 640, "Reject signs in \\uXXXX escape hex digits",
      Theme.ParserLimitsAndNumbers, PrState.Open,
      "`\"\\u+041\"` silently parsed and `\"\\u-041\"` threw a bare `IllegalArgumentException`; " +
        "both are parse errors with the usual message now."
    ),
    Contribution(
      Sconfig, 641, "Fix stale line numbers in parser errors after multiline strings (#625)",
      Theme.ParserLimitsAndNumbers, PrState.Open,
      "Parser errors raised after a multiline string named the line where the string began " +
        "instead of the offending statement's line."
    ),
    Contribution(
      Sconfig, 616, "fix: wrong line number for objects after multiline string",
      Theme.ParserLimitsAndNumbers, PrState.Open,
      "Objects after a triple-quoted string reported the wrong line in errors (ports #853)."
    ),
    Contribution(
      Sconfig, 614, "fix: origin line numbers after newline separators",
      Theme.ParserLimitsAndNumbers, PrState.Open,
      "An object or array starting on the line after `=` or `:` reported the key's line, one line " +
        "too high (ports #850)."
    ),
    Contribution(
      Sconfig, 605, "List expansion from environment variables",
      Theme.Ports, PrState.MergedUnreleased,
      "`${MY_LIST[]}` resolves to a list read from `MY_LIST_0`, `MY_LIST_1`, … — the " +
        "env-variable list suffix the specification allows and the formatter must refuse until " +
        "this ships (ports #833)."
    ),
    Contribution(
      Sconfig, 604, "Port lightbend/config#620+#686: override config with CONFIG_FORCE_* env vars",
      Theme.Ports, PrState.MergedUnreleased,
      "Opt-in `CONFIG_FORCE_<key>` environment overrides, off by default as upstream."
    ),
    Contribution(
      Sconfig, 603, "Port lightbend/config#619: let application.conf override reference.conf substitutions",
      Theme.Ports, PrState.MergedUnreleased,
      "`load()` resolves `reference.conf` together with `application.conf`, so the application " +
        "can override a value that `reference.conf` substitutes."
    ),
    Contribution(
      Sconfig, 602, "Port lightbend/config#708+#709: ConfigFactory.parseApplicationReplacement",
      Theme.Ports, PrState.MergedUnreleased,
      "The `config.resource` / `config.file` / `config.url` selection becomes a public " +
        "`parseApplicationReplacement`, so other loading strategies can reuse it."
    ),
    Contribution(
      Sconfig, 618, "Port lightbend/config#848: make SimpleConfigObject.keySet unmodifiable",
      Theme.Ports, PrState.MergedUnreleased,
      "Removing a key through the documented-immutable config's `keySet()` silently mutated it; " +
        "it now throws, like `withoutPath` does."
    ),
    Contribution(
      Sconfig, 615, "feat: opt-in unknown key validation for ConfigBeanFactory",
      Theme.Ports, PrState.Open,
      "An opt-in `create` overload that rejects config keys with no matching bean property, so a " +
        "typo such as `pool-sise` is reported instead of ignored (ports #851)."
    ),
    Contribution(
      Sconfig, 610, "Reject text after arrays and objects",
      Theme.Ports, PrState.Open,
      "Unquoted text after an array or object was silently dropped; it is an error now, as text " +
        "before one already is (ports #862)."
    ),
    Contribution(
      Sconfig, 469, "Porting from Lightbend: Support for huge memory units #663",
      Theme.Ports, PrState.Released,
      "Ports upstream's handling of huge memory-size units."
    ),
    Contribution(
      Sconfig, 458, "[ISSUE-455] showEnvVariableValues code ported, one bugfix",
      Theme.Ports, PrState.Released,
      "Ports `showEnvVariableValues`, with a bug fixed on the way."
    ),
    Contribution(
      Sconfig, 642, "Add property tests for parser bounds and renderer round-trip",
      Theme.Project, PrState.Open,
      "Deterministic property tests: parsing never escapes as anything but a `ConfigException`, " +
        "rendering is a fixed point, line numbers stay inside the input; test-only."
    ),
    Contribution(
      Sconfig, 622, "Run uncached full test suites in CI",
      Theme.Project, PrState.MergedUnreleased,
      "CI runs the uncached full suites, after sbt's incremental `test` reported cached suites as run."
    ),
    Contribution(
      Sconfig, 617, "Run the MiMa binary compatibility check in CI",
      Theme.Project, PrState.Closed,
      "Closed as superseded: the MiMa check and the uncached suites landed as two other pull requests."
    ),
    Contribution(
      Sconfig, 609, "Add AGENTS.md, a porting guide, and a porting skill",
      Theme.Project, PrState.Open,
      "Guidance for coding agents and a porting guide from lightbend/config that people can use " +
        "too; documentation only."
    )
  )

  // --- lightbend/config -------------------------------------------------------------------------

  private val lightbendConfig = List(    Contribution(
      LightbendConfig, 815, "Make formatting possible",
      Theme.FormatterProposal, PrState.Closed,
      "The proposal that began this project: rendering options so the library could back a " +
        "formatter — keep origin order, control indentation, choose the assign sign; closed " +
        "unmerged, and the equivalent options exist in sconfig as `ConfigFormatOptions`, which " +
        "hocon-fmt is built on."
    ),
    Contribution(
      LightbendConfig, 868, "Keep unresolved merge rendering stable across round trips",
      Theme.UnresolvedMerges, PrState.Open,
      "An unresolved merge — `a = [1]` then `a += 2` — rendered with a diagnostic banner that " +
        "parses back as comments, so the output grew on every cycle (208 to 2237 characters in " +
        "three passes); it now renders as repeated entries and stops growing."
    ),
    Contribution(
      LightbendConfig, 869, "Collapse partially resolved merge stacks that cannot become objects",
      Theme.UnresolvedMerges, PrState.Open,
      "A partially resolved list or text concatenation now hides the values below it, so its " +
        "rendering round-trips and completing the resolution no longer throws `BugOrBroken`."
    ),
    Contribution(
      LightbendConfig, 870, "Respect existing render options in unresolved merge entries",
      Theme.UnresolvedMerges, PrState.Open,
      "Unresolved-merge entries are rendered like ordinary fields, honouring the render options, " +
        "instead of hard-coded quoted keys with colons."
    ),
    Contribution(
      LightbendConfig, 871, "Keep comments above += fields off synthetic list elements",
      Theme.UnresolvedMerges, PrState.MergedUnreleased,
      "Comments above `a += 2` were copied onto the synthetic list element and rendered twice."
    ),
    Contribution(
      LightbendConfig, 867, "Keep list comment rendering stable across round trips",
      Theme.RendererRoundTrip, PrState.MergedUnreleased,
      "Rendering and re-parsing a list added one leading space to every element comment per " +
        "cycle; the spacing is stable now."
    ),
    Contribution(
      LightbendConfig, 878, "Fix BugOrBroken resolving a delayed merge inside a concatenated list piece (#751)",
      Theme.SubstitutionsAndConcatenations, PrState.Open,
      "Resolving a duplicate key whose later value is a substitution inside a concatenated list " +
        "piece threw `BugOrBroken`."
    ),
    Contribution(
      LightbendConfig, 879, "fix: look back in delayed merges for self-referential fields inside pieces",
      Theme.SubstitutionsAndConcatenations, PrState.Open,
      "A self-referential field inside a piece of a delayed merge now looks back at values from " +
        "earlier pieces (issue #728)."
    ),
    Contribution(
      LightbendConfig, 880, "fix: resolve substitutions that pass through object concatenations",
      Theme.SubstitutionsAndConcatenations, PrState.Open,
      "Substitutions that pass through an object concatenation resolve now instead of failing."
    ),
    Contribution(
      LightbendConfig, 881, "Fix BugOrBroken in resolveWith() when a delayed merge is not under the lookup root",
      Theme.SubstitutionsAndConcatenations, PrState.Open,
      "`resolveWith()` threw `BugOrBroken` when a delayed merge sat outside the lookup root " +
        "(issues #855, #332, #664)."
    ),
    Contribution(
      LightbendConfig, 882, "fix: += must append to values inherited through object concatenation",
      Theme.SubstitutionsAndConcatenations, PrState.Open,
      "`+=` must append to values inherited through an object concatenation, such as " +
        "`e = ${g} { … }` followed by `e += 2`; the five substitution PRs form one reviewed stack."
    ),
    Contribution(
      LightbendConfig, 874, "Limit parser collection nesting and wrap resolver stack overflow",
      Theme.ParserLimitsAndNumbers, PrState.Open,
      "Deeply nested collections and long `+=` chains failed with `StackOverflowError`; both " +
        "parsers now reject more than 100 levels and the resolver wraps its overflow."
    ),
    Contribution(
      LightbendConfig, 873, "Reject out-of-range long conversions and preserve exact boundaries",
      Theme.ParserLimitsAndNumbers, PrState.Open,
      "`getLong` clamped out-of-range values and NaN instead of throwing; out-of-range now " +
        "throws and exact boundaries are unchanged."
    ),
    Contribution(
      LightbendConfig, 872, "Render non-finite doubles as quoted strings",
      Theme.ParserLimitsAndNumbers, PrState.Open,
      "Non-finite doubles rendered as bare tokens, which strict JSON rejects; they render quoted now."
    ),
    Contribution(
      LightbendConfig, 875, "Hide CONFIG_FORCE override values when rendering environment values is disabled",
      Theme.EnvironmentOverrides, PrState.Open,
      "Values from `CONFIG_FORCE_*` overrides were printed even with environment values hidden, " +
        "because they carried a plain origin."
    ),
    Contribution(
      LightbendConfig, 876, "Load environment overrides lazily and avoid poisoning the cache holder",
      Theme.EnvironmentOverrides, PrState.Open,
      "A malformed `CONFIG_FORCE_*` variable broke cache invalidation for the rest of the JVM; " +
        "the overrides load lazily now."
    ),
    Contribution(
      LightbendConfig, 866, "Reuse patterns when parsing duration and memory size values",
      Theme.Performance, PrState.MergedUnreleased,
      "`getDuration` and `getMemorySize` compiled the same regex on every call; two precompiled " +
        "patterns now serve both."
    )
  )

  /** The whole snapshot, after both halves so no field initialises to null. */
  val all: List[Contribution] = sconfig ++ lightbendConfig
