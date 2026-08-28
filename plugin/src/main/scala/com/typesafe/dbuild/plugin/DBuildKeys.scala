package com.typesafe.dbuild.plugin

import com.typesafe.dbuild.model
import sbt.*

object DBuildKeys {
  // TODO - make a task that generates this metadata and just call it!
  type ArtifactMap = Seq[model.ArtifactLocation]
  @transient
  val extractArtifacts = TaskKey[ArtifactMap]("dbuild-extract-artifacts")
  // Used during the index generation
  @transient
  val moduleInfo = TaskKey[com.typesafe.dbuild.manifest.ModuleInfo]("module-info")
}