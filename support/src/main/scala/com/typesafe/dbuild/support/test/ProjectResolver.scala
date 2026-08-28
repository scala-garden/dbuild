package com.typesafe.dbuild.support.test

import _root_.java.net.URI
import _root_.sbt.io.FileFilter.{ globFilter => toFF }
import _root_.sbt.io.IO
import _root_.sbt.io.Path.*
import _root_.sbt.io.syntax.*
import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.model.*
import com.typesafe.dbuild.project.resolve.ProjectResolver
import java.io.File

/**
 * The test resolver does absolutely nothing; however, it fails every now and then (for testing).
 */
class TestProjectResolver() extends ProjectResolver {
  def canResolve(uri: String): Boolean = {
    uri == "test" || uri.startsWith("test:")
  }

  def resolve(config: ProjectBuildConfig, baseDir: File, log: Logger): ProjectBuildConfig = {
    // scrub the whole content before returning
    IO.delete(baseDir.*(toFF("*")).get())

    val rand = new java.util.Random
    // abort resolution 10% of the times
    if (rand.nextInt(10)==0)
      throw new Exception("Couldn't resolve, today..!")
    // rebuild 50% of the times
    if (rand.nextInt(2)==0) config.copy(setVersion=Some((rand.nextInt(80000)).toString)) else config

  }
}
