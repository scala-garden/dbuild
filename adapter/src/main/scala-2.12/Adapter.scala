package com.typesafe.dbuild.adapter
import java.io.File
import sbt.Keys.packagedArtifacts
import sbt.internal.BuildStructure
import sbt.internal.inc.ScalaInstance
import sbt.util.{ Logger, Show }
import sbt.{ Artifact, AttributeKey, Compile, ConfigKey, Def, InputTask, Reference, Scope, Settings, Task }

object Adapter extends AdapterCommon {
  def newIvyPaths(baseDirectory: java.io.File, ivyHome: Option[java.io.File]) =
    sbt.librarymanagement.ivy.IvyPaths(baseDirectory, ivyHome)

  // On sbt 1.x, Compile/packagedArtifacts is already a Map[Artifact, File]; this setting
  // just forwards it, so that shared code can use the same key name on both sbt versions.
  val packagedArtifactsSetting: Def.Setting[Task[Map[Artifact, File]]] =
    packagedArtifactsAsFiles := (packagedArtifacts in Compile).value

  def rescopeForTest(ref: Reference, conf: ConfigKey): Scope = Scope.ThisScope.in(ref, conf)
  def isKeyDefined(data: Settings[Scope], scope: Scope, key: AttributeKey[?]): Boolean =
    data.get(scope, key).isDefined
  def classifyKey(key: AttributeKey[?]): KeyKind = {
    val taskManifest = scala.reflect.ClassManifest.fromClass(classOf[Task[?]]).erasure
    val inputTaskManifest = scala.reflect.ClassManifest.fromClass(classOf[InputTask[?]]).erasure
    if (key.manifest.erasure == taskManifest) TaskKeyKind
    else if (key.manifest.erasure == inputTaskManifest) InputTaskKeyKind
    else OtherKeyKind
  }

  def reapplySettings(newSettings: Seq[Def.Setting[?]],
    structure: BuildStructure,
    log: Logger)(implicit display: Show[Def.ScopedKey[?]]): BuildStructure = {
      val ru = scala.reflect.runtime.universe
      val rm = ru.runtimeMirror(getClass.getClassLoader)
      val im = rm.reflect(Load)
      val reapplyAlternatives = ru.typeOf[Load.type].decl(ru.TermName("reapply")).
         asTerm.alternatives.map { s => s.asMethod }
      val reapplySymbol = reapplyAlternatives.find(_.paramLists(0).size == 2).
        getOrElse(reapplyAlternatives.find(_.paramLists(0).size == 3).
        getOrElse(sys.error("Internal error: no known reapply() found.")))
      val reapply = im.reflectMethod(reapplySymbol)
      (if (reapplySymbol.paramLists(0).size == 3)
        reapply(newSettings, structure, log, display)
       else
        reapply(newSettings, structure, display)
      ).asInstanceOf[BuildStructure]
    }

  /** Runtime exception representing a failure when finding a `ScalaInstance`. */
  class InvalidScalaInstance(message: String, cause: Throwable)
    extends RuntimeException(message, cause)

  protected def invalidScalaInstance(message: String, cause: Throwable): RuntimeException =
    new InvalidScalaInstance(message, cause)

  private def scalaInstanceHelper(libraryJar: File, compilerJar: File, extraJars: File*)(classLoader: List[File] => ClassLoader): ScalaInstance =
    {
      val loader = classLoader(libraryJar :: compilerJar :: extraJars.toList)
      val version = actualVersion(loader)(" (library jar  " + libraryJar.getAbsolutePath + ")")
      new ScalaInstance(version, loader, libraryJar, compilerJar, extraJars.toArray, None)
    }

  def scalaInstance(libraryJar: File, compilerJar: File, launcher: xsbti.Launcher, extraJars: File*): ScalaInstance =
    scalaInstanceHelper(libraryJar, compilerJar, extraJars: _*)(scalaLoader(launcher))
}
