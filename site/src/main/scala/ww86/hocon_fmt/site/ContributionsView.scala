package ww86.hocon_fmt.site

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.{Failure, Success}

import Browser.given

/** The page's third part: the author's pull requests against the libraries hocon-fmt depends on,
  * rendered from the shipped snapshot and refreshed in place by the live GitHub search when it
  * answers. Failure is quiet and visible: any repository that does not answer leaves its part of
  * the snapshot standing, and the state line says as much.
  */
object ContributionsView:

  private def prUrl(library: Library, number: Int): String =
    s"https://github.com/${library.repo}/pull/$number"

  def apply(): HtmlElement =
    val board = Var(Board.snapshot)
    // One quiet refresh per page load, guarded on every side: a completed future always sets
    // the board, so "checking GitHub…" cannot outlive the answer.
    refreshBoard.onComplete {
      case Success(updated) => board.set(updated)
      case Failure(_)       => () // per-library recovery makes this unreachable; the snapshot stands
    }

    sectionTag(
      idAttr := "contributions",
      h2("The work upstream"),
      p(
        "The formatter refuses a file when the library mis-renders it — and the same defects get ",
        "reported and fixed upstream. Below is what ",
        a(href := "https://github.com/kastoestoramadus", "kastoestoramadus"),
        " reported and proposed on the two libraries hocon-fmt depends on, grouped by library ",
        "and theme. A pull request counts as fixed only once a release carries it, so a merge ",
        "reads “merged upstream, not yet in a release”."
      ),
      p(
        cls       := "muted state-line",
        aria.live := "polite",
        child <-- board.signal.map(stateLine)
      ),
      div(children <-- board.signal.map(sections)),
      defectTable()
    )

  /** What the section knows: the snapshot alone, or the snapshot with some libraries refreshed. */
  final case class Board(
      entries: List[Contribution],
      others: Map[Library, List[LivePr]],
      asOf: String,
      liveLibraries: List[Library],
      failedLibraries: List[Library]
  ):
    def checking: Boolean = liveLibraries.isEmpty && failedLibraries.isEmpty

  object Board:
    val snapshot = Board(Contributions.all, Map.empty, Contributions.readOn, Nil, Nil)

  /** Every repository, cache first and the search after; a failure becomes a `Left`, so the
    * whole refresh always completes.
    */
  def refreshBoard(using http: GitHubApi.Http, storage: GitHubApi.Storage): Future[Board] =
    Future
      .sequence(Library.values.toList.map(refreshLibrary))
      .map { results =>
        val answered = results.collect { case Right(answered) => answered }
        val failed   = results.collect { case Left(library) => library }
        Board(
          entries = Contributions.all.map { entry =>
            answered
              .find(_._1 == entry.library)
              .flatMap(_._2.get(entry.number))
              .getOrElse(entry)
          },
          others = answered.collect { case (library, _, others) if others.nonEmpty => library -> others }.toMap,
          asOf = if answered.nonEmpty then Browser.today else Contributions.readOn,
          liveLibraries = answered.map(_._1),
          failedLibraries = failed
        )
      }

  private def refreshLibrary(library: Library)(using
      http: GitHubApi.Http,
      storage: GitHubApi.Storage
  ): Future[Either[Library, (Library, Map[Int, Contribution], List[LivePr])]] =
    val key                         = s"hocon-fmt-github:${library.repo}"
    def merged(items: List[LivePr]) =
      val result = Merge(Contributions.all.filter(_.library == library), items)
      (library, result.entries.map(e => e.number -> e).toMap, result.others)

    GitHubApi.readCache(storage, key, js.Date.now()) match {
      case Some(items) => Future.successful(Right(merged(items)))
      case None        =>
        GitHubApi
          .authorPrs(library.repo)
          .map { items =>
            GitHubApi.writeCache(storage, key, js.Date.now(), items)
            Right(merged(items))
          }
          .recover(_ => Left(library))
    }

  // --- rendering -------------------------------------------------------------------------------

  private def stateLine(board: Board): HtmlElement =
    if board.checking then span("checking GitHub…")
    else
      val scope =
        if board.failedLibraries.isEmpty then "refreshed from GitHub"
        else if board.liveLibraries.nonEmpty then
          s"snapshot; GitHub did not answer for ${board.failedLibraries.map(_.label).mkString(", ")}"
        else "the shipped snapshot"
      span(s"State as of ${board.asOf} ($scope).")

  private def sections(board: Board): List[HtmlElement] =
    if board.checking then Nil
    else
      Groups(board.entries).map { section =>
        div(
          cls := "library",
          h3(a(href := s"https://github.com/${section.library.repo}", section.library.label)),
          p(cls := "muted", section.library.blurb),
          section.groups.map { group =>
            div(
              cls := "theme",
              h4(group.theme.label),
              ul(cls := "entries", group.entries.map(entry(section.library)))
            )
          },
          board.others.get(section.library).filter(_.nonEmpty).map { others =>
            div(
              cls := "theme",
              h4("other recent work"),
              ul(cls := "entries", others.map(other(section.library)))
            )
          }
        )
      }

  private def entry(library: Library)(contribution: Contribution): HtmlElement =
    li(
      (
        List[Modifier[HtmlElement]](
          cls := "entry",
          a(href := prUrl(library, contribution.number), s"#${contribution.number}"),
          span(" — ")
        )
          ++ rich(contribution.note)
          :+ span(" ")
          :+ badge(contribution.state.label)
      )*
    )

  private def other(library: Library)(pr: LivePr): HtmlElement =
    li(
      cls := "entry",
      a(href := prUrl(library, pr.number), s"#${pr.number}"),
      " — ",
      pr.title
    )

  private def badge(text: String): HtmlElement =
    val style = text match {
      case "open"                                  => "badge open"
      case "in a release"                          => "badge released"
      case "merged upstream, not yet in a release" => "badge merged"
      case _                                       => "badge closed"
    }
    span(cls := style, text)

  /** The defect rows: what the formatter refuses, and what aims to fix it upstream. */
  private def defectTable(): HtmlElement =
    div(
      cls := "defects",
      h3("What the formatter refuses, upstream by pull request"),
      p(
        "Each row is an input, from the repository's known-limitations page, that the formatter ",
        "refuses because the library mis-renders it — the refusal the playground above shows — ",
        "and the pull requests that aim to fix it. The refusal names are the formatter's; the ",
        "playground explains each one when it happens."
      ),
      table(
        thead(tr(th("the input"), th("the refusal"), th("upstream"))),
        tbody(DefectTable.rows.map { row =>
          tr(
            td(code(row.defect)),
            td(code(row.refusal)),
            td(
              if row.fixes.isEmpty then span(cls := "muted", row.whenNoFix)
              else span(row.fixes.map(fix)*)
            )
          )
        })
      )
    )

  private def fix(link: PrLink): HtmlElement =
    Contributions.byNumber(link.library, link.number) match {
      case Some(entry) =>
        span(
          a(href := prUrl(link.library, link.number), s"${link.library.label} #${link.number}"),
          " ",
          badge(entry.state.label),
          " "
        )
      case None =>
        // An integrity test pins every row to the snapshot; this fallback exists only for the
        // reader if that test were ever weakened.
        span(cls := "muted", s"${link.library.label} #${link.number}")
    }

  /** `backtick` spans in the snapshot notes become code on the page; the rest stays text. */
  private def rich(text: String): List[Modifier[HtmlElement]] =
    text.split('`').zipWithIndex.toList.map { case (part, i) =>
      val fragment: Modifier[HtmlElement] =
        if i % 2 == 1 then code(part)
        else span(part)
      fragment
    }
