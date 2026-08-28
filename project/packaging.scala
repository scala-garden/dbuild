import com.typesafe.sbt.SbtNativePackager.*
import com.typesafe.sbt.packager.Keys.*
import sbt.*
import sbt.Keys.*
import scala.concurrent.ExecutionContext.Implicits.global

object Packaging {

  lazy val cleanMsg = TaskKey[Unit]("cleanMsg")

  def mapArt[A](key:sbt.TaskKey[?], kind: String) =
     // cheat a little: by setting the classifier to the version number, we can
     // publish to Ivy the tgz/zip with the full name, like "dbuild-0.8.1.tgz".
     Universal / key / artifact :=
       (Universal / key / artifact).value
          .withType(kind)
          .withExtension(kind)
          .withClassifier(Some(version.value))

  def settings(build:Project, repo:Project): Seq[Setting[?]] =
     SbtSupport.buildSettings ++ Seq(
     organization := "com.typesafe.dbuild",
     name := "dbuild",
     maintainer := "Antonio Cunei <antonio.cunei@lightbend.com>",
     packageSummary := "Multi-project builder.",
     packageDescription := """A multi-project builder capable of glueing together a set of related projects.""",
     Universal / name := name.value + "-" + version.value,
     rpmRelease := "1",
     rpmVendor := "typesafe",
     rpmUrl := Some("https://github.com/lightbend/dbuild"),
     rpmLicense := Some("BSD"),

     // NB: A clean must be executed before both packageZipTarball and packageZipTarball,
     // otherwise Universal may end up using outdated files.
     // The command "release" in root will perform a clean, followed by a publish.

     Compile / publishArtifact := false,

     // NB: A clean must be executed before both packageZipTarball and packageZipTarball,
     // otherwise Universal may end up using outdated files.

     cleanMsg := {streams.value.log.info("Cleaning dist...")},
     clean := clean.dependsOn(cleanMsg).value,

     publishLocal := publishLocal.dependsOn(clean).value,
     publish := publish.dependsOn(clean).value,

     publishMavenStyle := false,
     autoScalaLibrary := false,

     mapArt(packageZipTarball, "tgz"),
     mapArt(packageBin, "zip"),

     crossPaths := false

  ) ++
  addArtifact(Universal / packageZipTarball / artifact, Universal / packageZipTarball) ++
  addArtifact(Universal / packageBin / artifact, Universal / packageBin) ++
  Seq((Universal / mappings) ++= Seq(
        Packaging.makeDBuildProps(target.value, sourceDirectory.value, ((build / scalaVersion)).value, ((build / version)).value),
        Packaging.makeDRepoProps(target.value, sourceDirectory.value, ((build / scalaVersion)).value, ((build / version)).value),
        SbtSupport.sbtLaunchJar.value.head -> "bin/sbt-launcher.jar",
      )
  )

  def makeDRepoProps(t: File, src: File, sv: String, v: String): (File, String) = makeProps(t,src,sv,v,"repo","com.typesafe.dbuild.repo.core.SbtRepoMain")
  def makeDBuildProps(t: File, src: File, sv: String, v: String): (File, String) = makeProps(t,src,sv,v,"build","com.typesafe.dbuild.build.SbtBuildMain")

  private def makeProps(t: File, src: File, sv: String, v: String, name:String, clazz:String): (File, String) = {
    val tdir = t / "generated-sources"
    if(!tdir.exists) tdir.mkdirs()
    val tprops = tdir / ("d"+name+".properties")
    // TODO - better caching
    if(!tprops.exists) IO.write(tprops, """
[scala]
  version: %s

[app]
  org: com.typesafe.dbuild
  name: %s_3
  version: %s
  class: %s
  cross-versioned: false
  components: xsbti

[repositories]
  local
  maven-central
  sonatype-snapshots: https://oss.sonatype.org/content/repositories/snapshots
  sonatype-releases: https://oss.sonatype.org/content/repositories/releases
  jcenter: https://jcenter.bintray.com/
  java-annoying-cla-shtuff: https://download.java.net/maven/2/
  typesafe-releases: https://repo.typesafe.com/typesafe/releases
  typesafe-ivy-releases: https://repo.typesafe.com/typesafe/ivy-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  typesafe-ivy-snapshots: https://repo.typesafe.com/typesafe/ivy-snapshots, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  sbt-plugin-releases: https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
  jgit-repo: https://download.eclipse.org/jgit/maven
  scala-fresh-2.10.x: https://repo.typesafe.com/typesafe/scala-fresh-2.10.x/

[boot]
 directory: ${dbuild.boot.directory-${dbuild.global.base-${user.home}/.dbuild}/boot/}

[ivy]
  ivy-home: ${dbuild.ivy.home-${user.home}/.ivy2/}
  checksums: ${sbt.checksums-sha1,md5}
  override-build-repos: ${sbt.override.build.repos-false}
  repository-config: ${sbt.repository.config-${sbt.global.base-${user.home}/.sbt}/repositories}
""" format(sv, name, v, clazz))
    tprops -> ("bin/d"+name+".properties")
  }

}
