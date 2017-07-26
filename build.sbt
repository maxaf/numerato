import java.nio.file._
import java.nio.file.attribute._
import macroRevolver._

lazy val baseSettings = Seq(
  organization := "com.bumnetworks",
  version := "0.0.2",
  scalaVersion := crossScalaVersions.value.head,
  crossScalaVersions := Seq("2.12.2", "2.11.11"),
  initialCommands := """
    import numerato._
    import scala.reflect.runtime.universe._
  """,
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked",
    "-feature")) ++ scalariformSettings

lazy val deps = Seq(
 libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    "org.specs2" %% "specs2-core" % "3.9.1" % "test",
    "org.specs2" %% "specs2-matcher-extra" % "3.9.1" % "test"))

lazy val publishSettings = Seq(
  publishTo <<= (version) {
    v =>
    val repo = file(".") / ".." / "repo"
    Some(Resolver.file("repo",
      if (v.trim.endsWith("SNAPSHOT")) repo / "snapshots"
      else repo / "releases"))
  }
)

lazy val updateReadme = taskKey[Unit]("copy tut-generated README.md to project root")

lazy val core = project
  .in(file("."))
  .settings(baseSettings)
  .settings(MacroRevolverPlugin.useMacroParadise)
  .settings(deps)
  .settings(name := "numerato", moduleName := "numerato")
  .enablePlugins(TutPlugin)
  .settings(updateReadme := {
    val README = "README.md"
    tut.value.foreach {
      case (generated, README) =>
        Files.copy(
          Paths.get(generated.toURI),
          Paths.get(baseDirectory.value.toURI).resolve(README),
          StandardCopyOption.REPLACE_EXISTING
        )
      case _ =>
    }
  })
  .settings(publishSettings)

lazy val tests = project
  .in(file("tests"))
  .settings(baseSettings)
  .settings(deps)
  .settings(name := "tests", moduleName := "numerato-tests")
  .settings(publish := {})
  .settings(MacroRevolverPlugin.useMacroParadise)
  .settings(MacroRevolverPlugin.testCleanse)
  .dependsOn(core)

lazy val numerato = project
  .aggregate(core, tests)
  .settings(baseSettings)
