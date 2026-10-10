# 2026-10-09 — — — Refusing the definitions an include may be shadowed by

**Change:** The two inputs the formatter research recorded as "accepted but meaning changed" — B1
(`include "f.conf"`, `o = 3`, `o.c = 7`) and B2 (`x.a = 5`, the include, `x {}`) — are refused on
every platform now (`Refusal.ShadowedByInclude`). An include is masked as a placeholder field
before the parse, so the merge resolves the text as if the included file said nothing: a
definition a later one replaces, and an object that merges into an object beside it and adds
nothing, leave no trace in the rendered tree although either may have been what kept the included
file's values out of a path. `IncludeShadow` reads the definitions and includes of the masked text
from `DuplicateReport`'s document walk, and the parsed tree's origins say which lines survived the
merge, so a definition with no surviving line was dropped. It refuses a dropped definition that
stands in the include's object, that another definition of that object names on the same path or
one above or below it, and whose dropping could let values through: it writes a value and a later
definition makes the path an object again, or it is an object with nothing inside it and no later
definition of the path follows. The refusal names the include's line and the definition's line; the
CLI adds the ways out (delete the line, move it or the include, write the value as one definition),
the page explains the same, and docs/limitations.md carries the verified rewrites. The refusals
land in the java-api `RefusalKind` mirror, the page's `RefusalKind`, the web script's names, the
examples catalogue (one refused example per case) and the improvement entries. The generated
family in `HoconGen` crosses every shape of definition around an include, and
`IncludeShadowResolveSpec` resolves each case with its included file on disk before and after
formatting: the formatter must refuse or keep the values. Of the 524 files in `examples/`, the
golden files and the research probes, exactly the three reproducing B1 and B2 are refused that
were not before.

**Look at again before a release:** The check refuses a little too much by design — the included
file cannot be read at format time. The false refusals found: a dropped definition whose erasure a
later definition already did (a scalar followed by an object-valued definition of the same path),
and an empty object whose replacement an earlier object-valued definition had already made
redundant, are both allowed, but `x.a = 5`, the include, `x {}` is refused even when the included
file says nothing about `x` (research probe `470.conf`), and so is any file where a value-fetching
definition is dropped after an include while the path still ends in an object. Each is a file
whose include the formatter cannot read; if an integration ever supplies the included contents,
the check could be narrowed to the paths the include really writes. `IncludeShadow` also parses
the masked text's document tree — one more parse for files with an include — which
`scripts/bench.py` should confirm costs little on the include-bearing corpus.
