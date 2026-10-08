import sbt._
import sbt.Keys._
import wartremover.WartRemover
import wartremover.WartRemover.autoImport._

/** What the compiler has no flag for, in the code that ships: a partial call that throws on an
  * empty collection or a missing value, a null, an exception for control flow, a cast, mutable
  * state. The few places that need one say why in a `@SuppressWarnings`; a test may throw, since
  * that fails the test. A plugin rather than `ThisBuild`, so each project's Scala version decides.
  */
object Warts extends AutoPlugin {
  override def requires = WartRemover
  override def trigger  = allRequirements

  override def projectSettings: Seq[Setting[_]] = Seq(
    Compile / compile / wartremoverErrors := {
      if (scalaBinaryVersion.value == "3")
        Seq(
          Wart.AsInstanceOf,
          Wart.EitherProjectionPartial,
          Wart.IsInstanceOf,
          Wart.IterableOps,
          Wart.Null,
          Wart.OptionPartial,
          Wart.RedundantConversions,
          Wart.Return,
          Wart.Throw,
          Wart.TryPartial,
          Wart.Var,
          Wart.While
        )
      else Nil
    }
  )
}
