# 2026-10-08 — #61 — Placeholder checks get follow-ups

**Change:** Placeholder checks inspect unresolved object merges through their rendering; collision
probes find the first unused prefix with one scan per text, and the second formatting pass skips the
user-collision probe.

**Look at again before a release:** Keep unresolved-merge refusals accurate on both released sconfig
and the site fork; retain the dense-prefix regression and benchmark scenario.
