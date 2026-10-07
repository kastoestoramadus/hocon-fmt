package ww86.hocon_fmt.site

import ww86.hocon_fmt.site.Library.LightbendConfig
import ww86.hocon_fmt.site.Library.Sconfig

/** The main story, row by row: every input the formatter refuses because the library mis-renders
  * it (docs/limitations.md, `SconfigDefectsSpec`), and the pull requests that aim to fix it
  * upstream. A row without a fix says so; an include in a replaced object carries no fix because
  * merging repeated keys is by design — carrying includes across is the formatter's own job.
  */
object DefectTable:

  val rows: List[DefectRow] = List(
    DefectRow(
      "`a : [1]` then `a += 2`",
      RefusalKind.BrokenOutput,
      List(PrLink(Sconfig, 598), PrLink(LightbendConfig, 868)),
      DefectRow.noFixYet
    ),
    DefectRow(
      "`a : 1` then `a : ${a}`",
      RefusalKind.BrokenOutput,
      List(PrLink(Sconfig, 598), PrLink(LightbendConfig, 868)),
      DefectRow.noFixYet
    ),
    DefectRow(
      "`path = [ /bin ]` then `path = ${path} [ /usr/bin ]`",
      RefusalKind.BrokenOutput,
      List(PrLink(Sconfig, 599), PrLink(Sconfig, 598), PrLink(LightbendConfig, 869), PrLink(LightbendConfig, 868)),
      DefectRow.noFixYet
    ),
    DefectRow(
      "`path : \"a:b:c\"` then `path : ${path}\":d\"`",
      RefusalKind.BrokenOutput,
      List(PrLink(Sconfig, 599), PrLink(Sconfig, 598), PrLink(LightbendConfig, 869), PrLink(LightbendConfig, 868)),
      DefectRow.noFixYet
    ),
    DefectRow(
      "`foo : { a : { c : 1 } }` then `foo : ${foo.a}` then `foo : { a : 2 }`",
      RefusalKind.BrokenOutput,
      List(PrLink(Sconfig, 598), PrLink(LightbendConfig, 868)),
      DefectRow.noFixYet
    ),
    DefectRow(
      "`g = { size = 6 }` then `e = ${g} { name = \"east\" }`",
      RefusalKind.BrokenOutput,
      List(PrLink(Sconfig, 598)),
      DefectRow.noFixYet
    ),
    DefectRow(
      "a one-field object inside an array that does not fit on one line",
      RefusalKind.BrokenOutput,
      Nil,
      DefectRow.noFixYet
    ),
    DefectRow(
      "a comment with no field after it",
      RefusalKind.LostComment,
      Nil,
      DefectRow.noFixYet
    ),
    DefectRow(
      "an include in an object that a later definition of the key replaces",
      RefusalKind.LostInclude,
      Nil,
      "no pull request: merging repeated keys is by design, and carrying includes across is the formatter's own job"
    ),
    DefectRow(
      "`a : ${b}` with `b : ${a}`",
      RefusalKind.UnstableOutput,
      List(PrLink(Sconfig, 598), PrLink(LightbendConfig, 868)),
      DefectRow.noFixYet
    ),
    DefectRow(
      "an array at the file root",
      RefusalKind.NotHocon,
      Nil,
      DefectRow.noFixYet
    ),
    DefectRow(
      "the `[]` env-variable list suffix, `${MY_LIST[]}`",
      RefusalKind.NotHocon,
      List(PrLink(Sconfig, 605)),
      DefectRow.noFixYet
    ),
    DefectRow(
      "an object nested 32 or more deep (on Scala.js only)",
      RefusalKind.BrokenOutput,
      Nil,
      DefectRow.noFixYet
    ),
    DefectRow(
      "`a : include \"x\"`, a value read as a directive",
      RefusalKind.NotHocon,
      Nil,
      DefectRow.noFixYet
    )
  )
