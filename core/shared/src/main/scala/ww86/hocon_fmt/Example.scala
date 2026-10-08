package ww86.hocon_fmt

final case class ExampleSource(kind: String, pattern: String, url: Option[String], licence: Option[String])

/** Human-authored expectations, embedded at build time without runtime file access. */
final case class Example(
    id: String,
    title: String,
    story: String,
    shows: String,
    input: String,
    target: String,
    now: String,
    pending: Option[String],
    reasonIfDifferent: Option[String],
    findings: List[String],
    source: ExampleSource,
    options: List[String],
    expected: Map[String, String]
)
