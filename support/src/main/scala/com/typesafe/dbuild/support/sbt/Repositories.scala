package com.typesafe.dbuild.support.sbt

import _root_.java.io.File
import _root_.java.net.{ URI, URL }
import _root_.sbt.io.IO
import com.typesafe.dbuild.model.*

object Repositories {
  val ivyPattern = "[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
  def writeRepoFile(repos:List[xsbti.Repository], config: File, repositories: (String, String)*): Unit = {
    val sb = new StringBuilder("[repositories]\n")
    for((name, uri) <- repositories) {
      sb append (" ivy-%s: %s, %s\n" format(name, uri, ivyPattern))
      sb append (" mvn-%s: %s\n" format(name, uri))
    }
    repos foreach {
      case m:xsbti.MavenRepository => sb append ("  "+m.id+": "+m.url+"\n")
      case i:xsbti.IvyRepository => sb append ("  "+i.id+": "+i.url+", "+i.ivyPattern+"\n")
      case p:xsbti.PredefinedRepository => sb append ("  "+p.id+"\n")
    }
    IO.write(config, sb.toString)
  }

  private case class PredefRepo(id: xsbti.Predefined) extends xsbti.PredefinedRepository
  private case class MvnRepo(id: String, url: URL, allowInsecureProtocol: Boolean = false) extends xsbti.MavenRepository
  private case class IvyRepo(id: String, url: URL, ivyPattern: String, artifactPattern: String,
    mavenCompatible: Boolean = false, descriptorOptional: Boolean = false,
    skipConsistencyCheck: Boolean = false, allowInsecureProtocol: Boolean = false) extends xsbti.IvyRepository

  /**
   * Parses the same (id -> spec) shape that writeRepoFile()'s "[repositories]" entries
   * encode: `id -> None` for a predefined repository (matched against xsbti.Predefined's
   * labels, e.g. "local", "maven-central"), `id -> Some("url")` for a Maven repository, and
   * `id -> Some("url, ivyPattern")` for an Ivy repository.
   */
  def parseRepositories(entries: Iterable[(String, Option[String])]): List[xsbti.Repository] =
    entries.map {
      case (id, None) => PredefRepo(xsbti.Predefined.toValue(id))
      case (id, Some(spec)) =>
        spec.split(",", 2).map(_.trim) match {
          case Array(url) => MvnRepo(id, new URL(url))
          case Array(url, pattern) => IvyRepo(id, new URL(url), pattern, pattern)
          case z => sys.error("Internal error, unexpected split result: " + z.mkString(","))
        }
    }.toList
}
