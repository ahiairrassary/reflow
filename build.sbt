name := "reflow"

version := "1.0"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
    "org.scream3r" % "jssc" % "2.8.0",

    "org.scalafx" %% "scalafx" % "8.0.40-R8",

    "com.typesafe.akka" %% "akka-actor" % "2.4.2",

    "io.reactivex" %% "rxscala" % "0.25.1",

    // apache
    "commons-codec" % "commons-codec" % "1.10",
    "commons-io" % "commons-io" % "2.4",
    "org.apache.commons" % "commons-lang3" % "3.3.2"
)

fork := true