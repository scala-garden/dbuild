import sbt.*
import Keys.*

object SbtSupport {
  val sbtLaunchJarUrl = SettingKey[String]("sbt-launch-jar-url")
  val sbtLaunchJarLocation = SettingKey[File]("sbt-launch-jar-location")
  val sbtLaunchJar = TaskKey[Seq[java.io.File]]("sbt-launch-jar", "Resolves sbt launch jar")

  val buildSettings: Seq[Setting[?]] = Nil
  val settings: Seq[Setting[?]] = buildSettings ++ Seq(
    // The jar is added as a resource, so that the running dbuild can find it and use it to spawn new instances of sbt
    (Compile / resourceGenerators) += sbtLaunchJar.taskValue
  )
}
