# 2026-10-08 — #42 — The playground skips repeat formatting

**Change:** The playground avoids repeating formatting for unchanged input; contribution refresh
delivery belongs to the mounted section and failures settle to the snapshot. `ComponentSpec` mounts
both components in a fake DOM and fails on the old wiring; the snapshot and Laminar-practice docs
say what a remount really does.

**Look at again before a release:** Use keyed `split` before adding repeated list refreshes; a
remount re-runs the refresh (the ten-minute cache usually answers it) and rebuilds the list;
requests still finish or hit their deadline after unmount.
