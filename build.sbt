lazy val playSlickV = "3.0.0-M5"

lazy val `query-timeout` = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    version := "1.0-SNAPSHOT",
    scalaVersion := "2.12.2",
    libraryDependencies ++= Seq(
      guice,
      "com.typesafe.play" %% "play-slick" % playSlickV,
      "com.typesafe.play" %% "play-slick-evolutions" % playSlickV,
      "org.postgresql" % "postgresql" % "42.1.1",
      "com.h2database" % "h2" % "1.4.195",

      "org.scalatest" %% "scalatest" % "3.0.3" % Test,
      "org.scalatestplus.play" %% "scalatestplus-play" % "3.0.0-RC1" % Test,
      "org.scalacheck" %% "scalacheck" % "1.13.5" % Test,
      "org.scalamock" %% "scalamock-scalatest-support" % "3.6.0" % Test,
      "org.mockito" % "mockito-core" % "2.8.9" % Test
    )
  )
