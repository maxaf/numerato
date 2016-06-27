import scala.util.Try
import org.specs2._
import org.specs2.execute._, Typecheck._
import org.specs2.matcher.TypecheckMatchers._

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
      complete wildcard match               $wildcardCompleteMatch
      incomplete wildcard match w/guard     $wildcardIncompleteMatch
      lonely wildcard match w/guard         $wildcardIncompleteMatchAlone
      complete match                        $completeMatch
      incomplete match w/guard              $incompleteGuardedMatch
      incomplete match w/guard & w/wildcard $incompleteGuardedMatch
      illegal subtype of enum               $illegalSubtype
      complete with binding                 $completeWithBinding
      complete w/wildcard binding           $completeWithWildcardBinding

    invalid cases must not run:
      invalid index lookup                  $invalidIndexLookup
      invalid name lookup                   $invalidNameLookup

    enum values must (de)serialize          $javaSerialization
  """

  val validSingleton = Status must beAnInstanceOf[Status.type]
  val uniqueValues = Status.values.toSet must have length (6)
  val uniqueIndexes = Status.values.map(_.index).toSet must have length (6)
  val lookupByIndex = Status.fromIndex(0) must_== Status.Enabled
  val lookupByName = Status.fromName("Disabled") must_== Status.Disabled

  val incompleteMatch = typecheck {
    """
      import Status._
      Status switch {
        case Status.Enabled => true
      }
    """
  } must not succeed

  val wildcardCompleteMatch = typecheck {
    """Status switch {
      case _ => true
    }"""
  } must succeed

  val wildcardIncompleteMatchAlone = typecheck {
    """Status switch {
      case _ if System.currentTimeMillis % 2 == 0 => true
    }"""
  } must not succeed

  val wildcardIncompleteMatch = typecheck {
    """
      Status switch {
        case Enabled => "green"
        case Deferred | Pending => "yellow"
        case Disabled | Unknown | Challenged => "red"
        case _ if System.currentTimeMillis % 2 == 0 => true
      }
    """
  } must not succeed

  val completeMatch = typecheck {
    """
      import Status._
      Status switch {
        case Enabled => "green"
        case Deferred | Pending => "yellow"
        case Disabled | Unknown | Challenged => "red"
      }
    """
  } must succeed

  val incompleteGuardedMatch = typecheck {
    """
      import Status._
      Status switch {
        case Enabled if true => "green"
        case Deferred | Pending => "yellow"
        case Disabled | Unknown | Challenged => "red"
      }
    """
  } must not succeed

  val incompleteGuardedMatchWithWildcard = typecheck {
    """
      import Status._
      Status switch {
        case Enabled if true => "green"
        case Deferred | Pending => "yellow"
        case Disabled | Unknown | Challenged => "red"
        case _ => "grey"
      }
    """
  } must not succeed

  val completeWithBinding = typecheck {
    """
      import Status._
      Status switch {
        case s @ Enabled => "green"
        case s @ (Deferred | Pending) => "yellow"
        case Disabled | Unknown | Challenged => "red"
        case other => "grey"
      }
    """
  } must succeed

  val completeWithWildcardBinding = typecheck {
    """
      import Status._
      Status switch {
        case other => "does it matter?"
      }
    """
  } must succeed

  val illegalSubtype = typecheck {
    """
      case object DevilMayCare extends Status(666, "not in this life")
    """
  } must not succeed

  val invalidIndexLookup = Try(Status.fromIndex(10)) must beFailedTry.withThrowable[NoSuchElementException]
  val invalidNameLookup = Try(Status.fromName("foo")) must beFailedTry.withThrowable[NoSuchElementException]

  val javaSerialization = {
    import java.io._
    val out = new PipedOutputStream()
    val in = new PipedInputStream(out)
    new ObjectOutputStream(out).writeObject(Status.Enabled)
    val read = new ObjectInputStream(in).readObject().asInstanceOf[Status]
    read must_== Status.Enabled
  }
}
