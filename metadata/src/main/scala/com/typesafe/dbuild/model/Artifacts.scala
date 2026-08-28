package com.typesafe.dbuild.model
import CirceSupport.*
import io.circe.generic.semiauto.{ deriveEncoder, deriveDecoder }
import io.circe.{ Encoder, Decoder }
import java.io.File

object FileCodec {
  // Jackson's built-in File serializer normalizes to the absolute path; a relative
  // File written here would otherwise be resolved against the *reader's* working
  // directory (a different process, generally an sbt subprocess with its own cwd),
  // producing a wrong, doubled-up path.
  implicit val fileEncoder: Encoder[File] = Encoder.encodeString.contramap(_.getAbsolutePath)
  implicit val fileDecoder: Decoder[File] = Decoder.decodeString.map(new File(_))
}
import FileCodec.*

/**
 * This currently represents a "coordinate" of an artifact, the version you must
 * rewire to depend on, and the amount of time it took to build such an artifact.
 */
case class ArtifactLocation(info: ProjectRef, version: String, crossSuffix: String, pluginAttrs: Option[SbtPluginAttrs])
object ArtifactLocation {
  implicit val artifactLocationEncoder: Encoder[ArtifactLocation] = dropNullValues(deriveEncoder[ArtifactLocation])
  implicit val artifactLocationDecoder: Decoder[ArtifactLocation] = deriveDecoder[ArtifactLocation]
}

/**
 * If the artifact is an sbt plugin, it will have in its extraAttributes some additional information, that we will
 * need during rewiring. We store them here, and we set pluginAttrs in ArtifactLocation to Some(SbtPluginAttrs) accordingly.
 */
case class SbtPluginAttrs(sbtVersion: String, scalaVersion: String)
object SbtPluginAttrs {
  implicit val sbtPluginAttrsEncoder: Encoder[SbtPluginAttrs] = deriveEncoder[SbtPluginAttrs]
  implicit val sbtPluginAttrsDecoder: Decoder[SbtPluginAttrs] = deriveDecoder[SbtPluginAttrs]
}
/**
 * This class represents an Artifact's SHA (of the file) for unique storage and the
 * location it has in a maven/ivy/p2 repository.
 *
 * We use this to push files into artifactory and retrieve them as a workaround now.
 */
case class ArtifactSha(sha: String, location: String)
object ArtifactSha {
  implicit val artifactShaEncoder: Encoder[ArtifactSha] = deriveEncoder[ArtifactSha]
  implicit val artifactShaDecoder: Decoder[ArtifactSha] = deriveDecoder[ArtifactSha]
}

/**
 * This is the metadata a project generates after building.  We can deploy this to our repository as
 * as an immutable piece of data that is used to retrieve artifacts after the build.
 *
 * Note: The list of artifacts and files/shas is extracted for each subproject by the build system.
 */
case class ProjectArtifactInfo(
  project: RepeatableProjectBuild,
  versions: BuildArtifactsOut)
object ProjectArtifactInfo {
  implicit val projectArtifactInfoEncoder: Encoder[ProjectArtifactInfo] = deriveEncoder[ProjectArtifactInfo]
  implicit val projectArtifactInfoDecoder: Decoder[ProjectArtifactInfo] = deriveDecoder[ProjectArtifactInfo]
}

/**
 * This represents two pieces of data:
 *
 * (1) The artifacts that we need to rewire dependencies for
 * (2) The repository in which those artifacts are stored.
 *
 * BuildArtifactsIn represents "incoming artifacts to rewire", while
 * BuildArtifactsOut represents the "outgoing artifacts for publication".
 * The latter contains a sequence in which each element contains:
 * - name of a subproject
 * - artifacts published by that subproject
 * - corresponding shas of files published by that subproject to the repository.
 * The set of shas and artifacts should be related, in theory; in practice,
 * the file system is manually inspected, and any additional files that may
 * have been generated (checksums,additional metadata, etc) are grabbed as well
 * and turned into shas.
 *
 * If the build system has no subproject support, BuildArtifactsOut will contain
 * just one element, where the subproject name is the empty string.
 * 
 * The "space" field is not used to process or discover artifacts in any way
 * (that is done via the artifacts UUIDs): it is only used in diagnostic messages
 */
case class BuildArtifactsIn(artifacts: Seq[ArtifactLocation], fromSpace: String, localRepo: File)
object BuildArtifactsIn {
  implicit val buildArtifactsInEncoder: Encoder[BuildArtifactsIn] = deriveEncoder[BuildArtifactsIn]
  implicit val buildArtifactsInDecoder: Decoder[BuildArtifactsIn] = deriveDecoder[BuildArtifactsIn]
}
// variant for multi-level build systems
case class BuildArtifactsInMulti(materialized: Seq /*Levels*/ [BuildArtifactsIn]) {
  // to simplify single-level build systems, the following convenience methods
  // are supplied, which only refer to the first level
  def artifacts = materialized.head.artifacts
  def localRepo = materialized.head.localRepo
}
object BuildArtifactsInMulti {
  implicit val buildArtifactsInMultiEncoder: Encoder[BuildArtifactsInMulti] = deriveEncoder[BuildArtifactsInMulti]
  implicit val buildArtifactsInMultiDecoder: Decoder[BuildArtifactsInMulti] = deriveDecoder[BuildArtifactsInMulti]
}
case class BuildArtifactsOut(results: Seq[BuildSubArtifactsOut])
object BuildArtifactsOut {
  implicit val buildArtifactsOutEncoder: Encoder[BuildArtifactsOut] = deriveEncoder[BuildArtifactsOut]
  implicit val buildArtifactsOutDecoder: Decoder[BuildArtifactsOut] = deriveDecoder[BuildArtifactsOut]
}
// moduleInfo presents a view of the generated *modules*, while artifacts and shas refer to the
// generated *artifacts* (files). The two are related, but not strictly: information like version
// or name could differ, in theory. The two different views are carried around together as part
// of a "BuildSubArtifactsOut", which also includes the name of the subproject that refers to these
// artifacts and module info.
case class BuildSubArtifactsOut(subName: String, artifacts: Seq[ArtifactLocation], shas: Seq[ArtifactSha],
    moduleInfo: com.typesafe.dbuild.manifest.ModuleInfo)
object BuildSubArtifactsOut {
  implicit val buildSubArtifactsOutEncoder: Encoder[BuildSubArtifactsOut] = deriveEncoder[BuildSubArtifactsOut]
  implicit val buildSubArtifactsOutDecoder: Decoder[BuildSubArtifactsOut] = deriveDecoder[BuildSubArtifactsOut]
}

/**
 * This represents general information every dbuild must know:
 * What artifacts are coming in (from metadata) and where to
 * write new artifacts (so we can save them for later).
 * "version" is the version string that will result from the build
 * For subproj, see RepeatableProjectBuild.
 */
case class BuildInput(artifacts: BuildArtifactsInMulti, version: String, subproj: Seq /*Levels*/ [Seq[String]], outRepo: File, projectName: String)
object BuildInput {
  implicit val buildInputEncoder: Encoder[BuildInput] = deriveEncoder[BuildInput]
  implicit val buildInputDecoder: Decoder[BuildInput] = deriveDecoder[BuildInput]
}
