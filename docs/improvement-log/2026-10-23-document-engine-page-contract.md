# Document-engine page contracts

The page and web API now pin the same document output as the CLI: quoted spelling, comments,
duplicates, root arrays and same-line statements stay written. The page keeps the shared
byte-exact preamble promise and its resolved preview explicitly retains source key order.
Resolved values can still collapse duplicate definitions, omit comments or reject concatenations
that document formatting preserves; see docs/limitations.md.

Before release, run the complete plugin and platform matrix against a released sconfig carrying
both the document engine and fork fixes. The site's origin-order render still uses deprecated
sconfig options in one narrowly annotated method and needs a supported replacement before 3.0.0.
