# Scastie hand-off probe

Read [Scastie's README](https://github.com/scalacenter/scastie#configure-snippet-via-query-params)
and its current `Routing.scala`, `Inputs.scala`, `ApiRoutes.scala` and `BuildSettings.scala`.
The client decodes the `inputs` query parameter as `BaseInputs`; the current ADT envelope is
`{"SbtInputs":{...}}`, with `{"Scala3":{"scalaVersion":"3.8.2"}}` as the JVM target.
The page sends editable code separately from its own `sbtConfigExtra`; no editable build box.

curl against scastie.scala-lang.org, without cookies or credentials, returned:
- Pre-filled URL: `url HTTP 200 bytes 1277`.
- `POST /api/save` with JSON: `save HTTP 200 bytes 51`, returning
  `{"base64UUID":"A21RxU36TAm6yAvQDluyMA","user":null}`.
- `GET /api/snippets/A21RxU36TAm6yAvQDluyMA` returned the submitted code, Scala version,
  and `libraryDependencies += "eu.ww86" %% "hocon-fmt-core" % "0.1.0"` unchanged.
- Embed script: `embed HTTP 200 bytes 8058653`; no embed is loaded by our page.

Pick the pre-filled URL: no account, saved-resource mutation, embed script or server of ours.
A Playwright Chrome check loaded the pre-filled URL and saw the edited code rendered:
`Scastie pre-filled URL: edited code rendered in live browser: PASS`.
The site tests decode the exact generated URL and pin its code and build separately.
Long programs may exceed browser URL limits; clipboard hand-off is a future fallback for that case.

Scastie's own build editor remains editable. The restriction covers only what our page sends;
real remote enforcement needs our own service and isolation. Scastie also injects its own runtime.
Scala.js needs sjavatime, as the repo's web module does; transitive API dependencies are included.
The web global is a downloadable bundle, not a Maven Central artifact, so the Scala.js example
uses core directly. Java is copyable source and is not executed by Scastie's Scala runner.

Alternatives:
- Scastie embed: remote execution and editable build settings; adds an 8 MB script and third-party UI.
- Browser Scala compiler: experimental Chrome APIs, compiler/classpath assets and lazy loading; await probe.
- scala-cli to JS in WASM: requires a browser toolchain and linker distribution; no cheap proven path here.

Generated with gpt-6.1-sol/m through Codex
