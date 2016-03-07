package tests

import scala.util.Try
import numerato._
import org.specs2._
import org.specs2.execute._, Typecheck._
import org.specs2.matcher.TypecheckMatchers._

@enum class Status {
  val Enabled, Disabled = Value
}

class NumeratoSpec extends Specification {
  def is = s2"""
    the `@enum` annotation must generate:
      a singleton type                      $validSingleton
      unique values                         $uniqueValues
      unique indexes                        $uniqueIndexes
      lookup by index                       $lookupByIndex
      lookup by name                        $lookupByName

    invalid cases must not compile:
      incomplete match                      $incompleteMatch

    invalid cases must not run:
      invalid index lookup                  $invalidIndexLookup
      invalid name lookup                   $invalidNameLookup
  """

  val validSingleton = Status must beAnInstanceOf[Status.type]
  val uniqueValues = Status.values.toSet must have length (2)
  val uniqueIndexes = Status.values.map(_.index).toSet must have length (2)
  val lookupByIndex = Status.fromIndex(0) must_== Status.Enabled
  val lookupByName = Status.fromName("Disabled") must_== Status.Disabled

  val incompleteMatch = Try {
    (Status.Disabled: Status) match {
      case Status.Enabled => true
    }
  } must beFailedTry.withThrowable[MatchError]

  val invalidIndexLookup = Try(Status.fromIndex(10)) must beFailedTry.withThrowable[NoSuchElementException]
  val invalidNameLookup = Try(Status.fromName("foo")) must beFailedTry.withThrowable[NoSuchElementException]
}
