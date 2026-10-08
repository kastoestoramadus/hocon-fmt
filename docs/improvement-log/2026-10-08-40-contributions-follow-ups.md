# 2026-10-08 — #40 — Contribution snapshot follow-ups

**Change:** Drop null or undefined pull request items in live responses and cached answers; require
the browser deadline test to observe an abort.

**Look at again before a release:** Keep the bounded fake-fetch fallback when changing deadline
handling; it catches requests that never abort.
