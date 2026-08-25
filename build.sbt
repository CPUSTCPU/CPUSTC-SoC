ThisBuild / scalaVersion := "2.13.16"

lazy val chiselVersion = "6.7.0"

lazy val tensorCore = RootProject(file("IP/TensorCore").toURI)

lazy val root = (project in file("."))
  .dependsOn(tensorCore)
  .settings(
    name := "CPUSTCSoc",
    Compile / run / mainClass := Some("main.main"),

    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % Test,
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),

    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    )
  )
