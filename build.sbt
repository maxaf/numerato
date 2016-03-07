lazy val baseSettings = Seq(
  organization := "com.bumnetworks",
  version := "0.0.1-SNAPSHOT",
  scalaVersion := "2.11.7",
  initialCommands := """
    import numerato._
    import scala.reflect.runtime.universe._
  """,
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked",
    "-feature",
    "-language:experimental.macros")) ++ scalariformSettings ++ tutSettings

lazy val deps = Seq(
 libraryDependencies <+= (scalaVersion)("org.scala-lang" % "scala-reflect" % _),
 libraryDependencies ++= Seq(
    compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),
    "org.specs2" %% "specs2-core" % "3.7.2" % "test",
    "org.specs2" %% "specs2-matcher-extra" % "3.7.2" % "test"))

lazy val core = project
  .in(file("."))
  .settings(baseSettings)
  .settings(deps)
  .settings(name := "numerato", moduleName := "numerato")

lazy val tests = project
  .in(file("tests"))
  .settings(baseSettings)
  .settings(deps)
  .settings(name := "tests", moduleName := "numerato-tests")
  .settings(publish := {})
  .dependsOn(core)

lazy val numerato = project
  .aggregate(core, tests)
  .settings(baseSettings)
