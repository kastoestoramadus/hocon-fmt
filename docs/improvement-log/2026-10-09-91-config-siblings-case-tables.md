# 2026-10-09 — #91 — Config-sibling research case tables re-sourced

**Change:** A self-review of the merged config-sibling research pass (#87) found three case-table rows citing cases
their source files do not contain (a CRLF case in hclwrite's `TestFormat` table, an empty
`test-data/rewrite/nothing.toml`, jsonnetfmt goldens beyond the three that exist); they now name cases the files
hold — hclwrite's nested-list and template-interpolation cases, taplo's `comment_indentation` and rewrite corpus
file, jsonnetfmt's pass files and error-text goldens.

**Look at again before a release:** —
