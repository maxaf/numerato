# Numerato: easy deluxe enums for Scala

_enumerato_, _adj._: Italian for _enumerated_ (sans the extra _e_ because I
lack the gift of tongues)

## `scala.Enumeration` sucks

Everyone knows this, but I felt the need to point out the obvious:

```scala
// - singleton type tedious to use as an actual *type*
// - why should I extend some mysterious class?
object CrustyStatus extends Enumeration {
  type CrustyStatus = Value // far from DRY: I already called it `CrustyStatus`!

  val Enabled, Disabled = Value // this is probably the nicest part of `Enumeration`
}
// defined object CrustyStatus

// without this import I won't be able to access the `CrustyStatus` non-singleton type
import CrustyStatus._
// import CrustyStatus._

def isEnabled(s: CrustyStatus) = s match {
  case Enabled => true
  // non-exhaustive match not caught at compile time...
  // ...seriously, doesn't even emit a warning
}
// isEnabled: (s: CrustyStatus.CrustyStatus)Boolean
```

## Could we do better?

Of course! When in doubt, generate it with macros:

```scala
import numerato._
```

### Declaring an enumeration

Simply create a plain class, annotate it with `@enum`, and use the familiar
`val ... = Value` declaration to define a few enum values:

```scala
@enum class Status {
  val Enabled, Disabled = Value
}
// defined class Status
// defined object Status
```

The `@enum` annotation invokes a macro, which will:

* Replace your `Status` class with a sealed `Status` class suitable for acting
  as a base type for enum values. Specifically, it'll grow a `(val index: Int,
  val name: String)` constructor. These parameters will be supplied by the
  macro, so you don't have to worry about it.
* Generate a `Status` companion object, which will contain most of the pieces
  that now make `Status` an enumeration. This includes a `values:
  List[Status]`, plus lookup methods.

Give the above `Status` class, here's what the generated code looks like:

```scala
sealed abstract class Status(val index: Int, val name: String)(implicit sealant: Status.Sealant);
object Status {
  @scala.annotation.implicitNotFound(msg = "Enum types annotated with @enum can not be extended directly. To add another value to the enum, please adjust your `def ... = Value` declaration.")
  sealed abstract protected class Sealant;
  implicit protected object Sealant extends Sealant;
  case object Enabled extends Status(0, "Enabled") with scala.Product with scala.Serializable;
  case object Disabled extends Status(1, "Disabled") with scala.Product with scala.Serializable;
  val values: List[Status] = List(Enabled, Disabled);
  val fromIndex: _root_.scala.Function1[Int, Status] = Map(Enabled.index.->(Enabled), Disabled.index.->(Disabled));
  val fromName: _root_.scala.Function1[String, Status] = Map(Enabled.name.->(Enabled), Disabled.name.->(Disabled))
};
```

Please note that the

### Using the enumeration

#### Non-exhaustive matches

The main attraction is being able to `match {}` against an enum type & be
warned by the compiler about non-exhaustive matches. As shown way above,
`scala.Enumeration` doesn't support this, but `@enum` types do! Check this out:

```scala
def isEnabled(s: Status) = s match {
  case Status.Enabled => true
}
// <console>:22: warning: match may not be exhaustive.
// It would fail on the following input: Disabled
//        def isEnabled(s: Status) = s match {
//                                   ^
// isEnabled: (s: Status)Boolean
```

Non-exhaustive matches are still possible, but at least you'll be warned at
compile time.

#### Reflecting upon the enum

Enumeration values have numeric indexes, string names (no more
`getClass.getSimpleName`), and the `@enum`-generated companion provides lookup
methods that safely and easily turn indexes and names into instances of the
annotated type.

```scala
// each enum value has a auto-detected name
Status.Disabled.name
// res4: String = Disabled

// you can look up values by name
Status.fromName(Status.Enabled.name)
// res6: Status = Enabled

// or by the auto-generated zero-based index
Status.fromIndex(Status.Disabled.index)
// res8: Status = Disabled

// iterate over all values as needed
Status.values
// res10: List[Status] = List(Enabled, Disabled)
```

#### Safety & hygiene

In order to maintain integrity of the enumeration, `@enum` discourages intrepid
explorers: the enumeration type will be automatically sealed for your safety &
convenience.

Attempting to directly extend an `@enum`-annotated type will result in
compile-time errors. As seen above, the generated type is sealed, which makes
it impossible to derive from it in another compilation unit (aka file).

Additionally, the generated enum constructor requires an implicit `Sealant`
parameter, which is a protected inner type of the generated companion object.
This makes it impossible to derive from the enum type even within the same
file.
