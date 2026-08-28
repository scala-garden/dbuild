package com.typesafe.dbuild.model

import ClassLoaderMadness.withContextLoader
import com.typesafe.config.Config
import com.typesafe.config.ConfigException.Missing
import com.typesafe.config.ConfigFactory.{ parseString, parseFile }
import com.typesafe.config.ConfigRenderOptions
import io.circe.parser.parse
import io.circe.{ Decoder, Encoder, Json, JsonObject, ParsingFailure, DecodingFailure }
import java.io.File
import scala.concurrent.duration.*

/** Thrown on a JSON parsing/decoding failure; kept under this name for compatibility with callers
 *  that used to catch Jackson's JsonMappingException. */
class JsonMappingException(message: String, cause: Throwable) extends RuntimeException(message, cause) {
  def this(message: String) = this(message, null)
}

/**
 * Small helpers used to replicate, on top of Circe, the exact wire conventions the old
 * jacks/Jackson-based serialization relied on:
 *  - fields renamed via a kebab-case JSON key (formerly @JsonProperty(...)).
 *  - Option fields that are entirely omitted from the JSON object when None
 *    (formerly CaseClassSkipNulls(true)), rather than written as `null`.
 *  - the "Flex" single-value-or-array encoding used by the various SeqXxx wrapper types.
 */
object CirceSupport {
  /** Renames JSON object keys on the way out (Scala field name -> JSON name). */
  def renamedEnc[A](pairs: (String, String)*)(enc: Encoder.AsObject[A]): Encoder.AsObject[A] =
    Encoder.AsObject.instance { a =>
      val map = pairs.toMap
      JsonObject.fromIterable(enc.encodeObject(a).toIterable.map { case (k, v) => (map.getOrElse(k, k), v) })
    }
  /** Renames JSON object keys on the way in (JSON name -> Scala field name), the reverse of renamedEnc. */
  def renamedDec[A](pairs: (String, String)*)(dec: Decoder[A]): Decoder[A] = {
    val reverse = pairs.map(_.swap).toMap
    dec.prepare(_.withFocus(_.mapObject { obj =>
      JsonObject.fromIterable(obj.toIterable.map { case (k, v) => (reverse.getOrElse(k, k), v) })
    }))
  }

  /** Drops JSON-null-valued keys entirely, instead of writing them out as `null`. */
  def dropNullValues[A](enc: Encoder.AsObject[A]): Encoder.AsObject[A] =
    Encoder.AsObject.instance(a => JsonObject.fromIterable(enc.encodeObject(a).toIterable.filterNot(_._2.isNull)))

  /** The "Flex" encoding: a Seq of exactly one element is written as a bare value, not a one-element array. */
  def flexEncoder[T](enc: Encoder[T]): Encoder[Seq[T]] = Encoder.instance {
    case Seq(single) => enc(single)
    case s           => Json.arr(s.map(enc.apply): _*)
  }
  /** The "Flex" decoding: a bare value becomes a one-element Seq; a JSON array decodes elementwise. */
  def flexDecoder[T](dec: Decoder[T]): Decoder[Seq[T]] = Decoder.instance { c =>
    c.value.asArray match {
      case Some(arr) =>
        arr.foldLeft(Right(Vector.empty): Decoder.Result[Vector[T]]) { (acc, j) =>
          for { v <- acc; t <- dec.decodeJson(j) } yield v :+ t
        }.map(_.toSeq)
      case None => dec.decodeJson(c.value).map(Seq(_))
    }
  }
}

object Utils {
  import CirceSupport.*

  implicit val finiteDurationEncoder: Encoder[FiniteDuration] = Encoder.encodeString.contramap(_.toString)
  implicit val finiteDurationDecoder: Decoder[FiniteDuration] = Decoder.decodeString.emap { s =>
    Duration(s) match {
      case f: FiniteDuration if f > Duration("0 nanoseconds") => Right(f)
      case _ => Left("The duration " + s + " is invalid, it must be a positive, non-zero finite duration")
    }
  }

  private def wrapParseFailure[T](result: Either[io.circe.Error, T], expanded: => String): T = result match {
    case Right(t) => t
    case Left(e) =>
      throw new JsonMappingException(e.getMessage, e)
  }

  def readValueT[T](c: Config)(implicit d: Decoder[T]): T =
    withContextLoader(getClass.getClassLoader) {
      val expanded = c.resolve.root.render(ConfigRenderOptions.concise)
      wrapParseFailure(parse(expanded).flatMap(_.as[T]), expanded)
    }
  def readValue[T](f: File)(implicit d: Decoder[T]): T = readValueT[T](parseFile(f))
  def readValue[T](s: String)(implicit d: Decoder[T]): T = readValueT[T](parseString(s))
  def writeValue[T](t: T)(implicit e: Encoder[T]): String =
    withContextLoader(getClass.getClassLoader) { e(t).noSpaces }
  def writeValueFormatted[T](t: T)(implicit e: Encoder[T]): String =
    com.typesafe.config.ConfigFactory.parseString(writeValue(t)).root.render(ConfigRenderOptions.concise.setFormatted(true))
  def readProperties(f: File): Seq[String] = {
    val config = parseFile(f)
    // do not resolve yet! some needed vars may be in prop files which have not been parsed yet
    // resolve *only* the properties key, in case we are using env vars there
    val rendered = try {
      val value = config.root.withOnlyKey("properties").toConfig().resolve().getValue("properties")
      value.render(ConfigRenderOptions.concise)
    } catch {
      case e: Missing => "[]"
    }
    parse(rendered).flatMap(_.as[Seq[String]]) match {
      case Right(v) => v
      case Left(e)  => throw new JsonMappingException("The \"properties\" section contains unexpected data.", e)
    }
  }

  // specific simplified variant to deal with reading a path from a /possible/ Artifactory response,
  // as well as a possible response from Flowdock
  def readSomePath[T](s: String)(implicit d: Decoder[T]): Option[T] =
    withContextLoader(getClass.getClassLoader) {
      parse(s).flatMap(_.as[T]).toOption
    }

  // verify whether project/space names are legal
  //
  private val reserved = Seq("standard", "dbuild", "root", ".")
  private val validChars = (('a' to 'z') ++ ('0' to '9') ++ Seq('-', '_')).toSet
  private def testName(name: String, dotsAllowed: Boolean) = {
    val lower = name.toLowerCase
    if (reserved.contains(lower)) {
      sys.error("The names \"dbuild\", \"root\", \"standard\", and \".\" are reserved; please choose a different name: \"" + name + "\".")
    }
    // for projects, "default" is also illegal (but it's legal for spaces)
    if (!dotsAllowed && lower == "default") {
      sys.error("The project name \"default\"is reserved; please choose a different name.")
    }
    if (!(lower forall (c => validChars(c) || (dotsAllowed && c == '.')))) {
      sys.error("Names can only contain letters, numbers, dashes, underscores" + (if (dotsAllowed) ", and dots" else "") + ", found: \"" + name + "\".")
    }
    if (dotsAllowed && (name.startsWith(".") || name.endsWith("."))) {
      sys.error("Names cannot start or end with a dot, found: \"" + name + "\".")
    }
    if (dotsAllowed && name.contains("..")) {
      sys.error("Names cannot contain two consecutive dots, found: \"" + name + "\".")
    }
  }
  def testProjectName(name: String) = testName(name, dotsAllowed = false)
  def testSpaceName(name: String) = testName(name, dotsAllowed = true)

  // spaces-related utilities

  // returns the list of this space + all containing spaces
  def allParents(name: String): Seq[String] = {
    name.split('.').toList match {
      case first :: rest => rest.scanLeft(first)(_ + "." + _)
      case Nil => sys.error("Internal error: subdivided space name was empty")
    }
  }

  // returns the topmost parent of this space
  def topParent(name: String): String = {
    val index = name.indexOf('.')
    if (index < 0) name else name.substring(0, index)
  }

  /**
   * Returns true if a project that requires that its dependencies are
   * visible in the "from" space will be able to find them in one
   * of the spaces listed in the "to" list.
   * Meaning that either "from" or one of its parents is exactly found
   * in the "to" list.
   * if from is "", the project cannot see any other project's dependencies
   */
  def canSeeSpace(from: String, to: Seq[String]) = {
    // like:
    // allParents(from).toSet.intersect(to.toSet).nonEmpty
    // but done using simple string comparisons.
    from != "" && to.exists(t => t == from || from.startsWith(t + "."))
  }

  /**
   * Determine if two spaces are identical, or one is
   * in a space that is hierarchically a parent of the
   * other.
   * The meaning is: return true if, when publishing the
   * same artifacts to both spaces, you get a collision.
   * It actually returns Some(space) if that is where
   * the collision occurs, or None if no collision.
   * if a space is "", then artifacts end in /dev/null
   * and they are never retrieved, so no conflict
   */
  // Conceptually, this is equivalent to:
  //   allParents(one).contains(two) || allParents(two).contains(one)
  // but we can do less work by comparing just the strings.
  def collidingSpaces(one: String, two: String) =
    if (one == "" || two == "")
      None
    else if (one == two || two.startsWith(one + "."))
      Some(two)
    else if (one.startsWith(two + "."))
      Some(one)
    else
      None

  /**
   * Check whether any pair of elements from the first and second
   * sequences, respectively, are colliding.
   * Returns Some(collidingElement), or None if no collision.
   */
  def collidingSeqSpaces(one: Seq[String], two: Seq[String]): Option[String] = {
    // In principle the algorithm could be more efficient,
    // but the sizes of our sequences will normally be absolutely
    // tiny (one or two elements in most cases)
    def traverse[T](seq: Seq[String], item: T, f: (String, T) => Option[String]): Option[String] = seq match {
      case Nil => None
      case first :: rest =>
        f(first, item) match {
          case None => traverse(rest, item, f)
          case some => some
        }
    }
    def collidingSeq(item: String, seq: Seq[String]): Option[String] = traverse(seq, item, collidingSpaces)
    traverse(one, two, collidingSeq)
  }
}
