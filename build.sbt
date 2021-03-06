lazy val common = project
  .in(file("common"))
  .enablePlugins(BuildInfoPlugin)
  .settings(BuildSettings.commonBuildSettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.bigQuery,
      Dependencies.cats,
      Dependencies.circe,
      Dependencies.circeJawn,
      Dependencies.circeLiteral,
      Dependencies.circeParser,
      Dependencies.decline,
      Dependencies.analyticsSdk,
      Dependencies.badrows,
      Dependencies.igluClient,
      Dependencies.igluCoreCirce,
      Dependencies.schemaDdl,
      Dependencies.specs2,
      Dependencies.scalaCheck,
      Dependencies.jackson,
      Dependencies.nettyCodec,
      Dependencies.nettyCodecHttp,
      Dependencies.googleOauth
    )
  )

lazy val loader = project
  .in(file("loader"))
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JavaAppPackaging)
  .settings(BuildSettings.loaderBuildSettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.dataflowRunner,
      Dependencies.directRunner,
      Dependencies.metrics,
      Dependencies.slf4j,
      Dependencies.circeJawn,
      Dependencies.circeLiteral,
      Dependencies.scioCore,
      Dependencies.scioBigQuery,
      Dependencies.specs2,
      Dependencies.scalaCheck,
      Dependencies.specs2ScalaCheck,
      Dependencies.scioTest,
      Dependencies.nettyCodecHttp,
      Dependencies.fastjson,
      Dependencies.googleOauth
    )
  )
  .dependsOn(common)

lazy val streamloader = project
  .in(file("streamloader"))
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JavaAppPackaging)
  .settings(BuildSettings.streamloaderBuildSettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.fs2,
      Dependencies.slf4j,
      Dependencies.pubsubFs2Grpc,
      Dependencies.specs2,
      Dependencies.jackson,
      Dependencies.googleOauth
    )
  )
  .dependsOn(common)

lazy val mutator = project
  .in(file("mutator"))
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JavaAppPackaging)
  .settings(BuildSettings.mutatorBuildSettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.bigQuery,
      Dependencies.pubsub,
      Dependencies.catsEffect,
      Dependencies.circeLiteral,
      Dependencies.fs2,
      Dependencies.igluClient,
      Dependencies.specs2,
      Dependencies.scalaCheck,
      Dependencies.jackson,
      Dependencies.googleOauth
    )
  )
  .dependsOn(common)

lazy val repeater = project
  .in(file("repeater"))
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JavaAppPackaging)
  .settings(BuildSettings.repeaterBuildSettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.bigQuery,
      Dependencies.gcs,
      Dependencies.pubsub,
      Dependencies.pubsubFs2Grpc,
      Dependencies.slf4j,
      Dependencies.catsEffect,
      Dependencies.circeLiteral,
      Dependencies.httpClient,
      Dependencies.logging,
      Dependencies.fs2,
      Dependencies.specs2,
      Dependencies.scalaCheck,
      Dependencies.specs2ScalaCheck,
      Dependencies.jackson,
      Dependencies.googleOauth
    )
  )
  .dependsOn(common)

lazy val forwarder = project
  .in(file("forwarder"))
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JavaAppPackaging)
  .settings(BuildSettings.forwarderBuildSettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.dataflowRunner,
      Dependencies.directRunner,
      Dependencies.slf4j,
      Dependencies.scioCore,
      Dependencies.scioBigQuery,
      Dependencies.specs2,
      Dependencies.scalaCheck,
      Dependencies.nettyCodecHttp,
      Dependencies.fastjson,
      Dependencies.googleOauth
    )
  )
  .dependsOn(common)
