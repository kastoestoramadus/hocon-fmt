# Formatter examples

`showcase/NN-slug/` supplies the playground buttons in directory order.
`catalogue/slug/` is for tested examples that do not appear on the page.

Each directory contains `input.conf` and HOCON metadata in `example.conf`.
Successful examples also carry `expected/default.conf`; refused showcase examples need no expected file.
Refused catalogue examples use `expected/refused.txt` instead. The sbt source generator
embeds these files in core test sources on all three platforms and in site sources,
keeping them out of the published core artifacts.

See [the schema and testing contract](../docs/testing.md#shared-examples).

The six showcase stories are authored here under GPL-3.0 except `04-library-file`,
which contains the first eleven lines of Apache Pekko's reference.conf verbatim.
Its `source` pins the URL, commit SHA and Apache-2.0 licence; see [NOTICE](../NOTICE)
and [the licence](licences/Apache-2.0.txt). Both the original input and its formatted
site derivative remain attributed. All five previous buttons survive in `catalogue/`
as `messy`, `includes`, `comments`, `not-hocon` and `sconfig-defect`; none was removed.
