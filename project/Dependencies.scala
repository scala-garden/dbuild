import sbt.*
import Keys.*

object Dependencies {

  val scala212 = "2.12.21"
  val scala3 = "3.8.4"
  val mvnVersion = "3.5.2"

  val gigahorse = "com.eed3si9n" %% "gigahorse-apache-http" % "0.9.4"

  val typesafeConfig = "com.typesafe" % "config" % "1.2.1"

  val aetherVersion = "1.1.0"
  val aether         = "org.apache.maven.resolver" % "maven-resolver" % aetherVersion
  val aetherApi      = "org.apache.maven.resolver" % "maven-resolver-api" % aetherVersion
  val aetherSpi      = "org.apache.maven.resolver" % "maven-resolver-spi" % aetherVersion
  val aetherUtil     = "org.apache.maven.resolver" % "maven-resolver-util" % aetherVersion
  val aetherImpl     = "org.apache.maven.resolver" % "maven-resolver-impl" % aetherVersion
  val aetherConnectorBasic = "org.apache.maven.resolver" % "maven-resolver-connector-basic" % aetherVersion
  val aetherFile     = "org.apache.maven.resolver" % "maven-resolver-transport-file" % aetherVersion
  val aetherHttp     = "org.apache.maven.resolver" % "maven-resolver-transport-http" % aetherVersion
  val aetherWagon    = "org.apache.maven.resolver" % "maven-resolver-transport-wagon" % aetherVersion

  val ivy            = "org.scala-sbt.ivy" % "ivy" % "2.3.0-sbt-48dd0744422128446aee9ac31aa356ee203cc9f4"

  val mvnAether      = "org.apache.maven" % "maven-resolver-provider" % mvnVersion
  val mvnWagon       = "org.apache.maven.wagon" % "wagon-http" % "3.0.0"
  val mvnEmbedder    = "org.apache.maven" % "maven-embedder" % mvnVersion

  val circeVersion   = "0.14.16"
  val circeCore      = "io.circe" %% "circe-core" % circeVersion
  val circeGeneric   = "io.circe" %% "circe-generic" % circeVersion
  // circe-generic-extras is only needed on Scala 2.12: on Scala 3 we use circe-core's own
  // native io.circe.derivation.{ConfiguredEncoder,ConfiguredDecoder} instead (see CirceDerivationCompat).
  def circeGenericExtras(scala212: Boolean): Option[ModuleID] =
    if (scala212) Some("io.circe" %% "circe-generic-extras" % "0.14.4") else None
  val circeParser    = "io.circe" %% "circe-parser" % circeVersion
  val aws            = "com.amazonaws" % "aws-java-sdk" % "1.3.29"
  val uriutil        = "org.eclipse.equinox" % "org.eclipse.equinox.common" % "3.6.0.v20100503"
  val jline          = "jline" % "jline" % "2.14.2"

  val javaMail       = "javax.mail" % "mail" % "1.4.7"
  val commonsLang    = "commons-lang" % "commons-lang" % "2.6"
  val commonsIO      = "commons-io" % "commons-io" % "2.4"
  val jsch           = "com.jcraft" % "jsch" % "0.1.50"
  val oro            = "org.apache.servicemix.bundles" % "org.apache.servicemix.bundles.oro" % "2.0.8_6"
  val scallop        = "org.rogach" %% "scallop" % "6.0.0"

  val jgit           = "org.eclipse.jgit" % "org.eclipse.jgit" % "3.1.0.201310021548-r"

  val slf4jSimple    = "org.slf4j" % "slf4j-simple" % "1.7.7"


  // these dependencies change depending on the scala version

  def akkaActor(scala212: Boolean): Option[ModuleID] =
    Some("org.apache.pekko" %% "pekko-actor" % "1.7.0")

  def specs2(scala212: Boolean) =
    if (scala212)
      "org.specs2" %% "specs2-core" % "3.8.8" % "test"
    else
      "org.specs2" %% "specs2-core" % "5.9.1" % "test"

  def specs2It(scala212: Boolean) =
    if (scala212)
      "org.specs2" %% "specs2-core" % "3.8.8" % "it"
    else
      "org.specs2" %% "specs2-core" % "5.9.1" % "it"

  // Once new versions of sbt/launcher/libraryManagement/zinc etc are released, move the 2.12 dependencies to those versions
  // The sbt modules dependend on both the scala version and the sbt version; sometimes they are "provided", but not always

  def sbtIo(scala212: Boolean, v: String) =
    "org.scala-sbt" %% "io" % "1.12.2"

  def sbtIvy(scala212: Boolean, v: String) =
    if (scala212)
      "org.scala-sbt" %% "librarymanagement-ivy" % "1.0.4"
    else
      "org.scala-sbt" %% "librarymanagement-ivy" % v

  def sbtLogging(scala212: Boolean, v: String) =
    if (scala212)
      "org.scala-sbt" %% "util-logging" % "1.0.3"
    else
      "org.scala-sbt" %% "util-logging" % v

  def sbtCommand(scala212: Boolean, v: String) =
    if (scala212)
      "org.scala-sbt" %% "command" % v
    else
      "org.scala-sbt" %% "command" % v

  def sbtSbt(scala212: Boolean, v: String) =
    if (scala212)
      "org.scala-sbt" % "sbt" % v
    else
      "org.scala-sbt" % "sbt" % v

  def sbtLauncherInt(scala212: Boolean, v:String) = "org.scala-sbt" % "launcher-interface" % "1.6.2"

  // Official sbt-team compatibility shim, providing a unified API for sbt plugins that
  // cross-build for sbt 1.x and sbt 2.x (see https://github.com/sbt/sbt2-compat).
  def sbt2Compat(scala212: Boolean, v: String): ModuleID =
    if (scala212)
      "com.github.sbt" % "sbt2-compat_2.12_1.0" % "0.2.0"
    else
      "com.github.sbt" % "sbt2-compat_sbt2_3" % "0.2.0"

  // other dependencies that depend on whether scala is 2.10 or 2.12, but are only included in some cases
  def zincIf212(scala212: Boolean, v:String): Option[ModuleID] =
    if (scala212)
      Some("org.scala-sbt" %% "zinc" % "1.0.5")
    else
      Some("org.scala-sbt" %% "zinc-classpath" % "2.0.4")

  def scalaXmlAlways =
    libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
}
