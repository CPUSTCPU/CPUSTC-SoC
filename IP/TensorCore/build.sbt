ThisBuild / scalaVersion := "2.13.16"

lazy val chiselVersion = "6.7.0"

lazy val root = (project in file("."))
  .settings(
    name := "TensorCoreAxiApbIp",
    Compile / run / mainClass := Some("tensorcore.GenerateTensorCoreAxiApb"),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % Test,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    Test / parallelExecution := false,
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-unchecked"
    )
  )
