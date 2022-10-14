ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.9"

lazy val root = (project in file("."))
  .settings(
    name := "scala-school-classes"
  )

libraryDependencies ++= Seq(
  "com.squareup.okhttp3" % "okhttp" % "4.10.0",
  "com.lihaoyi" %% "upickle" % "1.6.0"
)
