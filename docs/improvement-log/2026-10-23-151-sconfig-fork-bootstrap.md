# One fork dependency for the document engine

**Change:** The document-engine branch uses one pinned `kastoestoramadus/sHOCON` commit for
core, CLI, Java API, plugins and the page. `scripts/fetch-sconfig-fork.sh` publishes JVM, JS and
Native artifacts to local Ivy and Maven repositories before builds; CI, release, Pages and the
pre-commit integration prepare the same dependency. A local source override supports testing a
fork commit before it is pushed, while the default source stays on GitHub.

**Before release:** Replace the fork only with a released sconfig that contains both
`ConfigDocument.format` and the fork's comment fixes. Follow `docs/site.md` and rerun the complete
platform and plugin suites. These local fork artifacts do not make a published formatter usable
from Maven Central by themselves.
