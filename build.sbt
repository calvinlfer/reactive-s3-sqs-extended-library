name := "alpakka-sqs-s3-reactive-extended-lib"

version := "0.1"

scalaVersion := "2.12.8"

scalacOptions in ThisBuild ++= Seq(
  "-language:_",
  "-Ypartial-unification",
  "-deprecation"
)

libraryDependencies ++= {
  val lightbend = "com.lightbend.akka"
  val circe = "io.circe"
  val alpakkaV = "1.0-M1"
  val circeV = "0.11.0"
  Seq(
    lightbend         %% "akka-stream-alpakka-sqs"  % alpakkaV,
    lightbend         %% "akka-stream-alpakka-s3"   % alpakkaV,
    circe             %% "circe-core"               % circeV,
    circe             %% "circe-generic"            % circeV,
    circe             %% "circe-parser"             % circeV,
    "org.typelevel"   %% "squants"                  % "1.3.0"
  )
}
