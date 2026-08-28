package com.typesafe.dbuild.build

import com.typesafe.config.ConfigFactory
import com.typesafe.dbuild.logging.{ConsoleLogger, Logger}
import com.typesafe.dbuild.model.*
import com.typesafe.dbuild.model.DBuildConfiguration
import com.typesafe.dbuild.model.Utils.readValueT
import com.typesafe.dbuild.project.build.LocalBuildRunner
import com.typesafe.dbuild.project.{ BuildSystem, BuildData }
import com.typesafe.dbuild.support.sbt.Repositories
import java.io.File
import org.specs2.mutable.Specification

// TDOO - Because this requires the sbt plugin to be published, we have to publsh locally
// before we can run integration tests.
object RewireSpec extends Specification {
  sequential

  "dbuild" should {
    "inject into the meta-meta-build (sbt 1.x)" in {
      injectIntoMetaMetaBuild(projectName = "InjectionTest1x", sbtVersion = "1.13.0", extractionVersion = "2.12.21")
    }
    "inject into the meta-meta-build (sbt 2.x)" in {
      // "standard" (rather than an explicit "3.8.4") avoids a genuine dbuild gap: the
      // extraction-side Scala-version override (DependencyAnalysis.fixExtractionScalaVersion2)
      // only rewrites Keys.scalaVersion and lets sbt's own legacy compiler-fetch fallback run,
      // which isn't Scala-3-aware and looks for the old "scala-compiler" module instead of
      // "scala3-compiler_3". sbt 2.0.7 already defaults to Scala 3.8.4 on its own, so no
      // override is needed here anyway.
      injectIntoMetaMetaBuild(projectName = "InjectionTest2x", sbtVersion = "2.0.7", extractionVersion = "standard")
    }
  }

  // Runs the same "meta-meta-build" injection test against whichever (sbt-version,
  // extraction-version) pair is given, so the same scenario can be exercised against
  // both the sbt 1.x and the sbt 2.x plugin variants. Each variant uses its own distinct
  // project name, so the two runs (which specs2 may execute concurrently) don't collide
  // on the same extraction/build directories.
  private def injectIntoMetaMetaBuild(projectName: String, sbtVersion: String, extractionVersion: String) = {
    sbt.IO.withTemporaryDirectory { projectDir =>

      // Build the config text from the same literal template the test always used (with the
      // sbt/extraction versions and project name as placeholders, substituted below), to avoid
      // disturbing the delicate quoting trick used to embed a literal, timestamp-suffixed
      // "name := ..." line.
      val configTemplate =
        """|build: {
           |  check-missing: [false, false, false]
           |  cross-version: standard
           |  space: test
           |  sbt-version: "SBT_VERSION_PLACEHOLDER"
           |  extraction-version: "EXTRACTION_VERSION_PLACEHOLDER"
           |  projects: [
           |    {
           |      name: PROJECT_NAME_PLACEHOLDER
           |      check-missing: false
           |      uri: "nil://"
           |      extra: {
           |        settings: [
           |          [],
           |          [],
           |          [
           |            "name := \"Hello-""".stripMargin + System.currentTimeMillis + """\""
           |          ]
           |        ]
           |        run-tests: false
           |      }
           |    }
           |  ]
           |}
           |""".stripMargin
      val config = configTemplate
        .replace("SBT_VERSION_PLACEHOLDER", sbtVersion)
        .replace("EXTRACTION_VERSION_PLACEHOLDER", extractionVersion)
        .replace("PROJECT_NAME_PLACEHOLDER", projectName)

      val endConfig = ConfigFactory.parseString(config)
      val conf = readValueT[DBuildConfiguration](endConfig)

      val repoStrings = Seq(
        "local", // this will point to <top>/.dbuild/topIvy/ivy2/local
        "maven-central",
        // this is where "publishLocal" pushed its modules (at least if the Ivy cache is in the default location)
        "localIvy: file:"+System.getProperty("user.home")+"/.ivy2/local, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]",
        "sonatype-snapshots: https://oss.sonatype.org/content/repositories/snapshots",
        "sonatype-releases: https://oss.sonatype.org/content/repositories/releases",
        "typesafe-releases: https://repo.typesafe.com/typesafe/releases",
        "typesafe-ivy-releases: https://repo.typesafe.com/typesafe/ivy-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]",
        "typesafe-ivy-snapshots: https://repo.typesafe.com/typesafe/ivy-snapshots, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]",
        "sbt-plugin-releases: https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]",
        "scala-fresh-2.10.x: https://repo.typesafe.com/typesafe/scala-fresh-2.10.x/"
      )
      val repoRecords = repoStrings map {
                      _.split(":", 2) match {
                        case Array(x) => (x, None)
                        case Array(x, y) => (x, Some(y))
                        case z => sys.error("Internal error, unexpected split result: " + z)
                      }}

      val repos = Repositories.parseRepositories(repoRecords)

      val main = new LocalBuildMain(repos, BuildRunOptions(CleanupOptions(), Timeouts(), true, true))
      val outcome = try {
        main.build(conf, projectName, None)
      } finally main.dispose()

      // TODO - We expect no output, but we do expet a successfull run.
      outcome.status().toString must equalTo("SUCCESS (project rebuilt ok)")
    }
  }
}
