package ww86.hoconfmt.site

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

import ww86.hoconfmt.Verdict

/** Exercises the page's observable graph on Node, without creating DOM elements. */
class ReactiveSpec extends munit.FunSuite {
  def settle(): Future[Unit] = {
    val done = Promise[Unit]()
    val _    = js.timers.setTimeout(30) { done.success(()); () }
    done.future
  }

  test("formatting starts immediately, ignores repeated text, and settles on the latest input") {
    val owner = new ManualOwner
    val input = Var("a = 1")
    val seen  = scala.collection.mutable.ListBuffer.empty[(String, Verdict)]
    val _     = Playground.verdicts(input.signal, "a = 1", debounceMs = 5).foreach(seen += _)(using owner)
    assertEquals(seen.map(_._1).toList, List("a = 1"))
    input.set("a = 1")
    settle()
      .flatMap { _ =>
        assertEquals(seen.size, 1)
        input.set("a = 2")
        input.set("a = 3")
        settle()
      }
      .flatMap { _ =>
        assertEquals(seen.map(_._1).toList, List("a = 1", "a = 3"))
        input.set("a = 4")
        input.set("a = 3")
        settle()
      }
      .map { _ =>
        assertEquals(seen.map(_._1).toList, List("a = 1", "a = 3"))
      }
      .andThen { case _ => owner.killSubscriptions() }
  }

  // The verdict is the playground's own: a parse failure names the playground as where it tripped.
  test("a refusal keeps the exact pasted text in the settled verdict") {
    val owner = new ManualOwner
    val text  = "[1, 2]\n"
    val seen  = scala.collection.mutable.ListBuffer.empty[(String, Verdict)]
    val _     = Playground.verdicts(Var(text).signal, text).foreach(seen += _)(using owner)
    assertEquals(seen.map(_._1).toList, List(text))
    assertEquals(seen.map(_._2).toList, List(Verdict.of(text, "playground")))
    assert(seen.exists { case (_, Verdict.Refused(_)) => true; case _ => false })
    owner.killSubscriptions()
  }

  test("a refresh result reaches its owner, but a late result after disposal cannot update the board") {
    val answer   = Promise[ContributionsView.Board]()
    val active   = new ManualOwner
    val disposed = new ManualOwner
    val seen     = scala.collection.mutable.ListBuffer.empty[String]
    val updates  = ContributionsView.boardUpdates(answer.future)
    val _        = updates.foreach(_ => { seen += "active"; () })(using active)
    val _        = updates.foreach(_ => { seen += "disposed"; () })(using disposed)
    disposed.killSubscriptions()
    val _ = answer.success(ContributionsView.Board.snapshot)
    settle()
      .map { _ => assertEquals(seen.toList, List("active")) }
      .andThen { case _ => active.killSubscriptions() }
  }

  test("an unexpected refresh failure settles the state line with the shipped snapshot") {
    val owner = new ManualOwner
    val seen  = scala.collection.mutable.ListBuffer.empty[ContributionsView.Board]
    val _     = ContributionsView
      .boardUpdates(Future.failed(new IllegalStateException("refresh")))
      .foreach(seen += _)(using owner)
    settle()
      .map { _ =>
        assertEquals(seen.size, 1)
        assert(seen.forall(board => !board.checking))
        assertEquals(seen.flatMap(_.entries).toList, Contributions.all)
        assertEquals(seen.flatMap(_.failedLibraries).toList, Library.values.toList)
      }
      .andThen { case _ => owner.killSubscriptions() }
  }
}
