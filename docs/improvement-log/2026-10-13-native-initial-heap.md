# Native CLI initial heap (2026-10-13)

The shipped Scala Native 0.5.12 CLI intermittently crashes while checking 100 small files with
Immix's default initial heap. Compile it with `-DGC_INITIAL_HEAP_SIZE=512m` to avoid collections
on this workload. `GC_INITIAL_HEAP_SIZE` in the environment still overrides the compiled default.
This is a mitigation: the underlying collector defect remains for workloads that trigger many
collections, including larger inputs or a smaller environment override.

## Reproduction and verification

`scripts/native-stress.sh <binary> [runs=200] [files=100]` creates already-formatted service
configurations, checks them repeatedly under `timeout -s KILL 20`, prints every exit-code count,
and fails on any nonzero exit. The test was committed before the build setting.

On Linux/WSL, both builds were tested under `systemd-run --user --scope -p MemoryMax=8G
-p MemorySwapMax=0`, with core dumps disabled by the script:

```text
Default heap, 200 runs / 100 files:
  0: 189
  137: 11
Compiled 512m, 200 runs / 100 files:
  0: 200
```

Every abnormal baseline run logged `Unhandled signal 11` and was killed at the timeout.
The CI CLI acceptance job and the pre-commit end-to-end script each run a shorter 100-run check
against the native binary they already link. This loop samples an intermittent failure; passing
it does not establish that the collector defect is fixed.

One-file startup medians over 25 successful runs, including the timeout launcher, were
39.945 ms before and 37.699 ms after. Peak RSS from `/usr/bin/time -v` on successful checks:

| Files | Default heap (KiB) | Compiled 512m (KiB) |
| --- | ---: | ---: |
| 1 | 40,320 | 40,000 |
| 100 | 141,280 | 283,360 |

The larger heap does not slow startup in this sample, but retains more memory on the 100-file
workload. The initial heap is a virtual-memory reservation, not a promise that all 512 MiB is
resident; restrictive virtual-memory limits can prevent startup.

## Before release and on every Scala Native upgrade

Rerun the 200-run stress loop on every Scala Native upgrade, alongside the regular test suites.
Also repeat startup and RSS measurements, and check environment-override behavior when changing
the runtime or heap option. Keep the compiled option while the collector defect remains.
When Scala Native fixes the collector, remove the option and verify the unpatched default with
the same stress loop before release; a single clean loop alone is not evidence of an upstream fix.
