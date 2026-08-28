package com.typesafe.dbuild.http

import gigahorse.HttpClient
import gigahorse.support.apachehttp.Gigahorse
import java.io.File
import sbt.io.IO
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

case class Credentials(user: String, pw: String)

class HttpTransfer(dbuildVersion: String) extends java.io.Closeable {

  private val http: HttpClient = Gigahorse.http(Gigahorse.config)

  def close(): Unit = http.close()

  private val userAgent = "dbuild/%s".format(dbuildVersion)
  private val encoding = java.nio.charset.StandardCharsets.UTF_8.toString()

  def download(uri: String, file: File, timeOut: Duration = 10.minutes): Unit = {
    // The uri is URL encoded in order to build a temporary
    // filename out of it
    val saneUri = java.net.URLEncoder.encode(uri, encoding)
    val suffix = saneUri.substring(Math.max(0, saneUri.length - 45))
    val absFile = file.getAbsoluteFile()
    IO.withTemporaryFile("dbuild-download", suffix) { tmp =>
      try {
        // Delete an old file; we do it always, to prevent
        // an incorrect download w/ exception to leave behind
        // an old file that may be misinterpreted as the new one.
        if (absFile.exists) absFile.delete()
        val request = Gigahorse.url(uri)
          .addHeaders("User-Agent" -> userAgent)
          .withFollowRedirects(true)
          .withRequestTimeout(timeOut)
        Await.result(http.download(request, tmp), timeOut)
        // did all go ok? Move the file to the right place.
        // Note that IO.move() may choke if the dest file is not absolute
        IO.move(tmp, absFile)
      } catch {
        case e: Exception =>
          throw new Exception("Error downloading " + absFile.getPath() + " from " + uri, e)
      }
    }
  }

  def upload(uri: String, file: File, cred: Credentials, timeOut: Duration = 10.minutes)(handleResponseBody: String => Unit): Unit = {
    val absFile = file.getAbsoluteFile()
    try {
      val request = Gigahorse.url(uri).put(absFile)
        .withAuth(cred.user, cred.pw)
        .addHeaders("User-Agent" -> userAgent)
        .withContentType("application/octet-stream")
        .withRequestTimeout(timeOut)
      val body = Await.result(http.run(request, Gigahorse.asString), timeOut)
      handleResponseBody(body)
    } catch {
      case e: Exception =>
        throw new Exception("Error uploading " + absFile.getPath() + " to " + uri, e)
    }
  }

}
