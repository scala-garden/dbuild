package sbt.dbuild.hack

import sbt.internal.{ BuildDef, Load => SbtLoad }
import sbt.librarymanagement.CrossVersion
import sbt.{ ExceptionCategory => SbtExceptionCategory, Keys }

object DbuildHack {
  val Load = SbtLoad
  val applyCross: (String, Option[String => String]) => String =
   CrossVersion.applyCross
  val defaultID: (java.io.File,String) => String =
   BuildDef.defaultID
  val ExceptionCategory = SbtExceptionCategory
  // projectDescriptors is private[sbt] in sbt 2.x; this forwarder (living inside a
  // subpackage of sbt) re-exposes it to the dbuild adapter.
  val projectDescriptors = Keys.projectDescriptors
}
