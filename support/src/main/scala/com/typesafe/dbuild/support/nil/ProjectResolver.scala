package com.typesafe.dbuild.support.nil

import _root_.sbt.io.FileFilter.{ globFilter => toFF }
import _root_.sbt.io.IO
import _root_.sbt.io.Path.*
import _root_.sbt.io.syntax.*
import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.model.*
import com.typesafe.dbuild.project.resolve.ProjectResolver
import java.io.File

/**
 * The nil resolver does absolutely nothing.
 */
class NilProjectResolver() extends ProjectResolver {
  def canResolve(uri: String): Boolean = {
    uri == "nil" || uri.startsWith("nil:")
  }

  def resolve(config: ProjectBuildConfig, baseDir: File, log: Logger): ProjectBuildConfig = {
    // scrub the whole content before returning
    IO.delete(baseDir.*(toFF("*")).get())
    config
  }
}
