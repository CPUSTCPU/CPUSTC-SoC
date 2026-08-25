ThisBuild / scalaVersion := "2.13.16"

lazy val spinalVersion = "1.14.1"

lazy val lcd = (project in file("."))
  .settings(
    name := "spinal-lcd",

    libraryDependencies ++= Seq(
      "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
      "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion,
      "com.github.spinalhdl" %% "spinalhdl-sim" % spinalVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.17" % Test,
      compilerPlugin(
        "com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion
      )
    )
  )
