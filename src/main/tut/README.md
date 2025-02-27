# Numerato: easy deluxe enums for Scala

[![Build Status](https://secure.travis-ci.org/maxaf/numerato.png?branch=master)](http://travis-ci.org/maxaf/numerato)

<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->


- [Numerato: easy deluxe enums for Scala](#numerato-easy-deluxe-enums-for-scala)
  - [`scala.Enumeration` sucks](#scalaenumeration-sucks)
  - [Could we do better?](#could-we-do-better)
    - [Declaring an enumeration](#declaring-an-enumeration)
    - [Enumeration flavors: plain vs parametric](#enumeration-flavors-plain-vs-parametric)
      - [Plain enumerations](#plain-enumerations)
      - [Parametric enumerations](#parametric-enumerations)
        - [Duplicate values](#duplicate-values)
    - [Using the enumeration](#using-the-enumeration)
      - [The `switch` statement](#the-switch-statement)
      - [Non-exhaustive regular `match`-es](#non-exhaustive-regular-match-es)
      - [Reflecting upon the enum](#reflecting-upon-the-enum)
      - [Safety & hygiene](#safety-&-hygiene)
  - [Using `numerato` in your project](#using-numerato-in-your-project)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

_enumerato_, _adj._: Italian for _enumerated_ (sans the extra _e_ because I
lack the gift of tongues)

## `scala.Enumeration` sucks

Everyone knows this, but I felt the need to point out the obvious:

```tut:book
// - singleton type tedious to use as an actual *type*
// - why should I extend some mysterious class?
object CrustyStatus extends Enumeration {
  type CrustyStatus = Value // far from DRY: I already called it `CrustyStatus`!

  val Enabled, Disabled = Value // this is probably the nicest part of `Enumeration`
}

// without this import I won't be able to access the `CrustyStatus` non-singleton type
import CrustyStatus._

def isEnabled(s: CrustyStatus) = s match {
  case Enabled => true
  // non-exhaustive match not caught at compile time...
  // ...seriously, doesn't even emit a warning
}
```

## Could we do better?

Of course! When in doubt, generate it with macros:

```tut:silent
import numerato._
```

### Declaring an enumeration

Simply create a plain class, annotate it with `@enum`, and use the familiar
`val ... = Value` declaration to define a few enum values.

The `@enum` annotation invokes a macro, which will:

* Replace your `Status` class with a sealed `Status` class suitable for acting
  as a base type for enum values. Specifically, it'll grow a `(val index: Int,
  val name: String)` constructor. These parameters will be supplied by the
  macro, so you don't have to worry about it.
* Generate a `Status` companion object, which will contain most of the pieces
  that now make `Status` an enumeration. This includes a `values:
  List[Status]`, plus lookup methods.

Give the above `Status` enum, here's what the generated code looks like:

```tut
@enum(debug = true) class Status {
  val Enabled, Disabled = Value
}
```

Note that you don't need to supply the `debug = true` part during normal use.
This is only useful for debugging, and is passed here to facilitate output of
generated code.

### Enumeration flavors: plain vs parametric

Parametric enumerations can carry arbitrary paylod data a-la Java's `enum`-s,
while plain enumerations don't carry any payload.

#### Plain enumerations

```tut
@enum class LocationPlain {
  val UWS, Chelsea = Value
}
```

A plain enumeration exposes only the auto-generated `index` and `name` fields.
It has no other accessors, and you can't make it carry around any additional
values.

#### Parametric enumerations

```tut
@enum class Neighborhood(zip: Int, elevation: Double) {
  val UWS = Value(zip = 10024, elevation = 40) // use named args
  val Chelsea = Value(10011, 19)               // or not!
}
```

A parametric enumeration is declared with a bunch of constructor parameters,
which are turned into public accessors on the generated enum type. These
accessors can be called like so:

```tut
Neighborhood.UWS.zip
Neighborhood.Chelsea.elevation
```

Please note that you cannot declare enum fields with the names `index` and
`name`, because currently these fields are auto-generated by the `@enum` macro
annotation, and are thus reserved.

```tut:fail
@enum class Dumb(name: Double)
```

```tut:fail
@enum class Dumber(index: AnyRef)
```

##### Duplicate values

`@enum` doesn't **currently** perform uniqueness checks on the
values of parametric enumerations. In practice this means that the following is
a valid enumeration:

```tut
@enum class Duplicity(mandatory: Boolean) {
  val Mandatory = Value(true)
  val Required = Value(true)
  val HellNo = Value(false)
}
```

Incidentally, this is also the behavior of Java's enumeration. If you have an
opinion on the subject, please drop by #3 and let your voice be heard.

### Using the enumeration

#### The `switch` statement

The `@enum`-generated companion comes with a `switch` construct that can be
used similarly to Scala's built-in `match` statement, but comes with additional
compile-time safety checks. Here's how it's used:

```tut
import Status._

val statuses = Enabled :: Disabled :: Enabled :: Enabled :: Disabled :: Nil

statuses.map(Status switch {
  case Status.Disabled => "not working"
  case Status.Enabled  => "working for sure"
})
```

From the above follows that the return type of `switch` is `Status => A`, where
`A` is the right hand side type representing the match result. In other words,
`switch` converts the provided partial function into a total function, and
ensures at compile time that the total function will not throw a `MatchError`
at runtime.

In order to complete its mission, `switch` imposes certain rules on code that
may appear within a `switch` block:

* All declared enum values must be matched, either `Individually` or in
  `Alternate | Groups` of `Absolutely | Any | Size`. In case of an incomplete
  match an error message will be shown explaining which enum values are
  missing:
```tut:fail
statuses.map(Status switch {
  case Status.Disabled => "not working"
})
```
* If you don't wish to specify all enum values, you must include a wildcard
  pattern that will act as a catch-all, thus making the match total:
```tut
statuses.map(Status switch {
  case Status.Disabled => "not working"
  case _ => "-unknown-"
})
```
* Guards cannot be used within a `switch` construct. There are no restrictions
  on the right hand side of a `=>` pattern, so feel free to write `if`
  statements or other conditionals there. Guards can't be evaluated at compile
  time, making it impossible to guarantee that the match will be complete:
```tut:fail
statuses.map(Status switch {
  case s @ Status.Disabled if s.name == "Enabled" => "not working"
  case _ => "-unknown-"
})
```
```tut:fail
statuses.map(Status switch {
  case Status.Disabled => "not working"
  case _ if System.currentTimeMillis % 2 == 0 => "-unknown-"
})
```

#### Non-exhaustive regular `match`-es

Scala's built-in `match` statement works with `@enum` as usual. The main
attraction is being able to `match {}` against an enum type & be warned by the
compiler about non-exhaustive matches. As shown way above, `scala.Enumeration`
doesn't support this, but `@enum` types do! Check this out:

```tut
def isEnabled(s: Status) = s match {
  case Status.Enabled => true
}
```

Non-exhaustive matches are still possible, but at least you'll be warned at
compile time. You're free to use either `switch` or `match` depending on your
needs. Just be aware that nothing stops you from ignoring `match`
incompleteness warnings, while `switch` will refuse to compile if something
smells funky.

#### Reflecting upon the enum

Enumeration values have numeric indexes, string names (no more
`getClass.getSimpleName`), and the `@enum`-generated companion provides lookup
methods that safely and easily turn indexes and names into instances of the
annotated type.

```tut:book
// each enum value has a auto-detected name
Status.Disabled.name

// you can look up values by name
Status.fromName(Status.Enabled.name)

// or by the auto-generated zero-based index
Status.fromIndex(Status.Disabled.index)

// iterate over all values as needed
Status.values
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

If you wish to add new values to the enum type, simply adjust the `val ... =
Value` declaration to include more values. `@enum` will take care of the rest.

## Using `numerato` in your project

First, add the resolver & dependency to your SBT build:

```scala
resolvers += "maxaf-releases" at s"http://repo.bumnetworks.com/releases/"

libraryDependencies += "com.bumnetworks" %% "numerato" % "0.0.2"
```

Next, enable macros & add a dependency on Macro Paradise:

```scala
resolvers += Resolver.sonatypeRepo("releases")

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)

scalacOptions += "-language:experimental.macros"
```

At last, proceed to define your enumerations as above:

```tut
import numerato._
@enum class Foo {
  val Bar, Baz, Quux = Value
}
```
