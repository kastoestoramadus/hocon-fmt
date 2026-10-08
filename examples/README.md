# Formatter examples

`showcase/NN-slug/` supplies the playground buttons in directory order.
`catalogue/slug/` is for tested examples that do not appear on the page.

Each directory contains `input.conf`, HOCON metadata in `example.conf`, and
`expected/default.conf` (unchanged input for refused showcase examples).
Refused catalogue examples use `expected/refused.txt` instead. The sbt source generator
embeds these files for tests on all three platforms and for the site.

See [the schema and testing contract](../docs/testing.md#shared-examples).
