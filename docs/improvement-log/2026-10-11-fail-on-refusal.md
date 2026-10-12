# Opt-in failure on refusals

CLI and all four build-tool plugins can fail on any selected formatting refusal with
`fail-on-refusal = true`. The default remains false for mixed directories. Explicit false
settings override repository true values independently of `fail-on-duplicates`. Refused bytes
are never written, and the CLI summary counts skipped (refused) files. Stdin refusals continue
to exit 1. File exit codes compose with OR, with I/O, usage and config errors taking precedence
as 2. Usage now describes a successful check in terms of the selected policy and reflects the
plugins' existing repository-option lookup.

Before release, rerun the CLI policy combinations on JVM, Node and Native, the Java isolated
loader contract, and the sbt, Gradle, Maven and Mill policy fixtures. Check both commands,
repository discovery, explicit false overrides, independent duplicate failures, and untouched
refused bytes. Keep the compatibility default when changing file selection or refusal reporting.
