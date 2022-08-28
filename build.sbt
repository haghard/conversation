val scala3Version = "3.1.3"
val scala2Version = "2.13.8"

val akka = "2.6.19"
val akkaHttp = "10.2.9"

val scalac3Settings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-language:implicitConversions",
    "-unchecked",
    "-Ykind-projector",
    "-Ysafe-init", //guards against forward access reference
    "-Xfatal-warnings",
    //"-Ytasty-reader",
  ) ++ Seq("-rewrite", "-indent") ++ Seq("-source", "future")
)

lazy val root = project
  .in(file("."))
  .settings(scalac3Settings)
  .settings(
    name := "conversation",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "com.madgag.spongycastle" % "core" % "1.58.0.0",
    )
  ).aggregate(scala2).dependsOn(scala2)

lazy val scala2 = project
  .in(file("scala2"))
  .settings(
    name := "scala2",
    scalaVersion := scala2Version,
    //Scala 2 dependencies
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.2.11",
      "com.typesafe.akka" %% "akka-actor-typed" % akka,
      "com.typesafe.akka" %% "akka-slf4j" % akka,
      "com.typesafe.akka" %% "akka-http"  % akkaHttp,
      "com.typesafe.akka" %% "akka-http2-support" % akkaHttp,
      "com.typesafe.akka" %% "akka-discovery" % akka,
      "com.typesafe.akka" %% "akka-stream" % akka,
      "com.github.pureconfig" %% "pureconfig" % "0.17.1",
      "io.spray" %% "spray-json" % "1.3.6",
      //"ru.odnoklassniki" % "one-nio" % "1.5.0",
    )
  ).enablePlugins(AkkaGrpcPlugin)

val jarName = "conversation.jar"
//assembly/assemblyJarName := jarName

scalafmtOnCompile := true

addCommandAlias("c", "compile")
addCommandAlias("r", "reload")

//comment out for test:run
//run / fork := true
//run / connectInput := true

javaOptions ++= Seq("-XX:+PrintCommandLineFlags", "-XshowSettings:vm", "-Xmx600M", "-XX:MaxMetaspaceSize=450m", "-XX:+UseG1GC")
