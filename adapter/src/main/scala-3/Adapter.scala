package com.typesafe.dbuild.adapter
import java.io.File
import sbt.Keys.{ fileConverter, packagedArtifacts }
import sbt.internal.BuildStructure
import sbt.internal.inc.{ InvalidScalaInstance, ScalaInstance }
import sbt.internal.util.KeyTag
import sbt.librarymanagement.IvyPaths
import sbt.util.{ Logger, Show }
import sbt.{ *, given }
import sbtcompat.PluginCompat.virtualFileRefToFile

object Adapter extends AdapterCommon:
  def newIvyPaths(baseDirectory: File, ivyHome: Option[File]) =
    IvyPaths(baseDirectory.getAbsolutePath, ivyHome.map(_.getAbsolutePath))

  // sbt 2.x's Compile/packagedArtifacts returns Map[Artifact, HashedVirtualFileRef] rather than
  // Map[Artifact, File]; this setting re-materializes the plain File map, using Keys.fileConverter
  // to resolve each virtual file reference to a real path.
  val packagedArtifactsSetting: Def.Setting[Task[Map[Artifact, File]]] = {
    packagedArtifactsAsFiles := Def.uncached {
      val conv = fileConverter.value
      val arts = (Compile / packagedArtifacts).value
      arts.map { case (a, ref) =>
        a -> virtualFileRefToFile(ref)(using conv)
      }
    }
  }

  def rescopeForTest(ref: Reference, conf: ConfigKey): Scope =
    Scope.ThisScope.rescope(ref).rescope(conf)
  def isKeyDefined(data: Def.Settings, scope: Scope, key: AttributeKey[?]): Boolean =
    data.get(Def.ScopedKey(scope, key)).isDefined
  def classifyKey(key: AttributeKey[?]): KeyKind =
    key.tag match
      case _: KeyTag.Task[?] => TaskKeyKind
      case _: KeyTag.InputTask[?] => InputTaskKeyKind
      case _ => OtherKeyKind

  def reapplySettings(newSettings: Seq[Def.Setting[?]],
    structure: BuildStructure,
    log: Logger)(implicit display: Show[Def.ScopedKey[?]]): BuildStructure =
      // sbt 2.x's Load.reapply() went back to a plain two-arg signature
      // (plus an implicit Show), so no version-detecting reflection is needed here.
      Load.reapply(newSettings, structure)

  protected def invalidScalaInstance(message: String, cause: Throwable): RuntimeException =
    new InvalidScalaInstance(message, cause)

  private def scalaInstanceHelper(libraryJar: File, compilerJar: File, extraJars: File*)(classLoader: List[File] => ClassLoader): ScalaInstance =
    val loader = classLoader(libraryJar :: compilerJar :: extraJars.toList)
    val version = actualVersion(loader)(" (library jar  " + libraryJar.getAbsolutePath + ")")
    // sbt 2.x's ScalaInstance separates the compiler-only/library-only classloaders
    // and takes plural jar arrays; dbuild only ever has a single shared loader here.
    new ScalaInstance(version, loader, loader, loader,
      Array(libraryJar), Array(compilerJar), (libraryJar +: compilerJar +: extraJars).toArray, None)

  def scalaInstance(libraryJar: File, compilerJar: File, launcher: xsbti.Launcher, extraJars: File*): ScalaInstance =
    scalaInstanceHelper(libraryJar, compilerJar, extraJars*)(scalaLoader(launcher))
end Adapter
