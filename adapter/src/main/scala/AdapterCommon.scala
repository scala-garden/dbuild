package com.typesafe.dbuild.adapter

import java.io.File
import org.apache.ivy.core.module.descriptor.ModuleDescriptor
import org.apache.ivy.core.module.id.ModuleRevisionId
import sbt.dbuild.hack.DbuildHack
import sbt.internal.librarymanagement.{ ProjectResolver => IvyProjectResolver }
import sbt.io.PathFinder
import sbt.librarymanagement.RawRepository
import sbt.{ Artifact, TaskKey }
import xsbti.Launcher

// The members below are identical across the sbt-1.x (scala-2.12) and sbt-2.x (scala-3)
// Adapter variants, so they live here, in the source dir shared by both cross-builds,
// rather than being duplicated in each of adapter/src/main/scala-{2.12,3}/Adapter.scala.
//
// Note: plain re-exports of stable sbt symbols (val IO = sbt.io.IO, type ModuleID =
// sbt.librarymanagement.ModuleID, etc.) have been removed from this trait entirely;
// callers should import those directly from sbt (e.g. `import sbt.io.IO`) instead of
// going through Adapter. Only genuine helpers and the dbuild-specific hack-package
// forwarders remain here.

/**
 * Members of the `Adapter` object that are identical whether the target is sbt 1.x
 * (Scala 2.12) or sbt 2.x (Scala 3). The per-Scala-version `Adapter` objects extend
 * this trait and add only the members that genuinely differ between the two sbt APIs.
 */
trait AdapterCommon {
  def allPaths(f:File) = PathFinder(f).allPaths

  def interProjectResolver(k:Map[ModuleRevisionId, ModuleDescriptor]) =
    new RawRepository(new IvyProjectResolver("inter-project", k), "inter-project")

  // Coursier-native counterpart to interProjectResolver(), above: sbt has used coursier
  // (not Ivy) as its default dependency-resolution engine since sbt 1.3, on both the sbt-1.x
  // and sbt-2.x lines, and its coursier integration exposes this as sbt.Keys.csrInterProjectDependencies
  // (a TaskKey[Seq[lmcoursier.definitions.Project]]) rather than as a Resolver. Unlike the
  // Ivy-based resolver, coursier's engine needs no full dependency graph for these entries --
  // just enough identity (module coordinates + version) for it to recognize the module as
  // already known, rather than going out to fetch it.
  def csrInterProjectDependencies(k: Map[ModuleRevisionId, ModuleDescriptor]): Seq[lmcoursier.definitions.Project] =
    k.keys.map { mr =>
      lmcoursier.definitions.Project(
        lmcoursier.definitions.Module(
          lmcoursier.definitions.Organization(mr.getOrganisation),
          lmcoursier.definitions.ModuleName(mr.getName),
          Map.empty),
        mr.getRevision,
        Seq.empty,
        Map.empty,
        Seq.empty,
        None,
        Seq.empty,
        lmcoursier.definitions.Info("", "", Seq.empty, Seq.empty, None)
      )
    }.toSeq

  val Load = DbuildHack.Load
  val applyCross = DbuildHack.applyCross
  def defaultID(base: File, prefix: String = "default") =
   DbuildHack.defaultID(base, prefix)
  val projectDescriptorsKey = DbuildHack.projectDescriptors

  // sbt 1.x/2.x disagree on the Compile/packagedArtifacts value type (File vs a virtual
  // file reference); this key is bound to a version-specific setting (see each Adapter
  // variant's packagedArtifactsSetting) that normalizes it back to a plain File map.
  val packagedArtifactsAsFiles: TaskKey[Map[Artifact, File]] =
    TaskKey[Map[Artifact, File]]("dbuild-packaged-artifacts-as-files")

  // The following bits abstract over the sbt-1.x-vs-sbt-2.x differences in the low-level
  // Scope/AttributeKey machinery, used to dynamically look up and run an arbitrary named
  // task/input-task (see DBuildRunner.buildStuff's doTestTask).
  sealed trait KeyKind
  case object TaskKeyKind extends KeyKind
  case object InputTaskKeyKind extends KeyKind
  case object OtherKeyKind extends KeyKind

  // These bits are inappropriately copied from various versions of zinc; some have been
  // removed and some made private, but we need them.
  // See: internal/zinc-classpath/src/main/scala/sbt/internal/inc/ScalaInstance.scala

  /** The prefix being used for Scala artifacts name creation. */
  val VersionPrefix = "version "

  /** Builds the version-appropriate "invalid Scala instance" exception. */
  protected def invalidScalaInstance(message: String, cause: Throwable): RuntimeException

  /** Gets the version of Scala in the compiler.properties file from the loader.*/
  protected def actualVersion(scalaLoader: ClassLoader)(label: String): String = {
    // Fast path: read the version straight from compiler.properties.
    try {
      val stream = scalaLoader.getResourceAsStream("compiler.properties")
      try {
        val props = new java.util.Properties
        props.load(stream)
        props.getProperty("version.number")
      } finally stream.close()
    } catch {
      // Slow path: fall back to invoking scala.tools.nsc.Properties.versionString reflectively.
      case e: Exception =>
        val scalaVersion = {
          try {
            Class
              .forName("scala.tools.nsc.Properties", true, scalaLoader)
              .getMethod("versionString")
              .invoke(null)
              .toString
          } catch {
            case cause: Exception =>
              val msg = s"Scala instance doesn't exist or is invalid: $label"
              throw invalidScalaInstance(msg, cause)
          }
        }
        if (scalaVersion.startsWith(VersionPrefix))
          scalaVersion.substring(VersionPrefix.length)
        else scalaVersion
    }
  }

  protected def scalaLoader(launcher: Launcher): Seq[File] => ClassLoader = { jars =>
    import java.net.{ URL, URLClassLoader }
    new URLClassLoader(
      jars.map(_.toURI.toURL).toArray[URL],
      launcher.topLoader
    )
  }
}
