ThisBuild / scalaVersion := "2.13.16"

lazy val spinalVersion = "1.14.1"

lazy val usb = (project in file("."))
  .settings(
    name := "spinal-usb",

    libraryDependencies ++= Seq(
      "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
      "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion,
      "com.github.spinalhdl" %% "spinalhdl-sim" % spinalVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.14" % Test,
      compilerPlugin(
        "com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion
      )
    )
  )
