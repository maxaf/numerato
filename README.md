# Numerato: easy deluxe enums for Scala

_enumerato_, _adj._: Italian for _enumerated_ (sans the extra _e_ because I
lack the gift of tongues)

## `scala.Enumeration` sucks

Everyone knows this, but I felt the need to point out the obvious:

```scala
scala> :paste
// Entering paste mode (ctrl-D to finish)

object Status /* singleton type tedious to use as a *type* */
  extends Enumeration /* why should I extend some mysterious type? */ {
  type Status = Value // far from DRY: I already called it `Status`!

  val Enabled, Disabled = Value // this is probably the nicest part of `Enumeration`
}

// without this import I won't be able to access the `Status` non-singleton type
import Status._

def isEnabled(s: Status) = s match {
  case Status.Enabled => true
  // non-exhaustive match not caught at compile time...
  // ...seriously, doesn't even emit a warning
}

// Exiting paste mode, now interpreting.

defined object Status
import Status._
isEnabled: (s: Status.Status)Boolean

scala> isEnabled(Disabled)
scala.MatchError: Disabled (of class scala.Enumeration$Val)
//                                   ^ what is this???
  at .isEnabled(<console>:18)
    ... 33 elided
// it died at runtime because we weren't warned at compile time
```

## Could we do better?

Of course! When in doubt, generate it with macros:

```scala
scala> :paste
// Entering paste mode (ctrl-D to finish)

import numerato._

// just annotate your enumeration type with @enum, nothing to extend
// it's a plain class, which you can use as any regular type
@enum class Status {
  val Enabled, Disabled = Value
}

def isEnabled(s: Status) = s match {
  // non-exhaustive matches are still possible, BUT...
  case Status.Enabled => true
}

// Exiting paste mode, now interpreting.

<console>:22: warning: match may not be exhaustive.
It would fail on the following input: Disabled
       def isEnabled(s: Status) = s match {
// you'll be warned at compile time!
```

There are also some extra goodies here:

```scala
// each enum value has a auto-detected name
scala> Status.Disabled.name
res0: String = Disabled

// you can look up values by name
scala> Status.fromName(Status.Enabled.name)
res1: Status = Enabled

// or by the auto-generated zero-based index
scala> Status.fromIndex(Status.Disabled.index)
res2: Status = Disabled

// iterate over all values as needed
scala> Status.values
res3: List[Status] = List(Enabled, Disabled)
```

In addition, `@enum` tries to discourage intrepid explorers - enum type will be
automatically sealed for your safety & convenience:
```scala
scala> object Undecided extends Status(-1, "make my day")
<console>:19: error: illegal inheritance from sealed class Status
       object Undecided extends Status(-1, "make my day")
```

Note that deriving from an `@enum`-annotated type is disallowed even within the
same compilation unit. To add new values to the enum, simply adjust your `val
... = Value` declaration.
