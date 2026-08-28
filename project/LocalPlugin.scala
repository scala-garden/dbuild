import sbt.{ *, given }
import Keys.*

object LocalPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override lazy val projectSettings = Seq(
    Compile / scalacOptions ++= {
      scalaBinaryVersion.value match {
        case "2.12" => Vector("-Xsource:3", "--release:8")
        case _      => Vector.empty
      }
    }
  )
}
