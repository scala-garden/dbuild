package com.typesafe.dbuild.model

import CirceDerivationCompat.*
import CirceSupport.*
import com.typesafe.dbuild.deploy.DeployTarget
import com.typesafe.dbuild.hashing
import io.circe.{ Encoder, Decoder, Json, JsonObject }
import scala.concurrent.duration.*

import SeqBooleanH.*
import SeqDBCH.*
import SeqDepsModifiersH.*
import SeqNotificationH.*
import SeqSelectorElementH.*
import SeqSeqStringH.*
import SeqStringH.*
/**
 * Metadata about a build.  This is extracted from a config file and contains enough information
 * to further extract information about a build.
 */
case class ProjectBuildConfig(name: String,
  system: String = "sbt",
  uri: String = "nil",
  setVersion: Option[String],
  // if both set-version and set-version-suffix are specified,
  // then set-version will take precedence
  setVersionSuffix: Option[String],
  deps: SeqDepsModifiers = Seq.empty,
  // the default crossVersion for ProjectBuildConfig is None:
  // that means the values will be taken from the enclosing
  // ProjectOptions record
  crossVersion: Option[Seq /*Levels*/ [String]] = None,
  // the default checkMissing None: works in the same manner as crossVersion
  checkMissing: Option[Seq /*Levels*/ [Boolean]] = None,
  // the default rewriteOverrides None: works in the same manner as crossVersion
  rewriteOverrides: Option[Seq /*Levels*/ [Boolean]] = None,
  useJGit: Option[Boolean] = None,
  space: Option[Space] = None,
  extra: Option[ExtraConfig]) {
  // after the initial expansion
  // you can use getExtra() to obtain the extra content
  def getExtra[T] = extra match {
    case Some(t: T @unchecked) => t
    case None => sys.error("Internal error: \"extra\" has not been expanded in project " + name + ". Please report.")
    case _ => sys.error("Internal error: \"extra\" has the wrong type in project " + name + ". Please report.")
  }

  // There are three levels at play. The innermost is the
  // ProjectBuildConfig, the outer one is the ProjectOptions, and if
  // neither defines anything, use CrossVersionsDefaults.defaults, which
  // is also used to fill the positions in the sequences beyond what may
  // have been defined. Each project may specify CrossVersions as an
  // Option[Seq], meaning that if no definition is present then it
  // is None, if an empty array is present, then Some(Seq()), etc.
  // At some point, each project is processed via expandDefaults,
  // below, in which the None definitions are replaced with the
  // general ones offered by the ProjectOptions. From that moment
  // on, the sequence will be completed for the missing positions
  // using corresponding elements from the infinite stream supplied
  // by CrossVersionsDefaults.defaults().

  // call getCrossVersionHead() only after defaults expansion (if at all)
  def getCrossVersionHead = crossVersion match {
    case None | Some(Seq()) => CrossVersionsDefaults.defaults.head
    case Some(seq) => seq.head
  }
  // call getCheckMissingHead() only after defaults expansion (if at all)
  def getCheckMissingHead = checkMissing match {
    case None | Some(Seq()) => getCrossVersionHead != "standard"
    case Some(cm) => cm.head
  }
  // call getRewriteOverridesHead() only after defaults expansion (if at all)
  def getRewriteOverridesHead = rewriteOverrides match {
    case None | Some(Seq()) => RewriteOverridesDefaults.defaults.head
    case Some(ro) => ro.head
  }

  def expandDefaults(defaults: ProjectOptions) = {
    val cv = crossVersion getOrElse defaults.crossVersion: Seq[String]
    val cm = checkMissing getOrElse defaults.checkMissing: Seq[Boolean]
    val ro = rewriteOverrides getOrElse defaults.rewriteOverrides: Seq[Boolean]
    val jg = useJGit getOrElse defaults.useJGit
    val sp = space getOrElse defaults.space
    copy(crossVersion = Some(cv), checkMissing = Some(cm), rewriteOverrides = Some(ro), useJGit = Some(jg), space = Some(sp))
  }

  def getCommit = try Option((new java.net.URI(uri)).getFragment) catch {
    case e: java.net.URISyntaxException => None
  }

  // sanity check on the project name
  Utils.testProjectName(name)
}
object CrossVersionsDefaults {
  def defaults = "disabled" +: Stream.continually("standard")
}
object RewriteOverridesDefaults {
  def defaults = Stream.continually(true)
}

// Used only for decoding: mirrors ProjectBuildConfig, but with "extra" left as raw JSON,
// since which concrete ExtraConfig subtype it holds depends on the sibling "system" field.
private case class ProjectBuildConfigRaw(name: String,
  system: Option[String] = None,
  uri: Option[String] = None,
  setVersion: Option[String],
  setVersionSuffix: Option[String],
  deps: Option[SeqDepsModifiers] = None,
  crossVersion: Option[SeqString /*Levels*/ ] = None,
  checkMissing: Option[SeqBoolean /*Levels*/ ] = None,
  rewriteOverrides: Option[SeqBoolean /*Levels*/ ] = None,
  useJGit: Option[Boolean] = None,
  space: Option[Space] = None,
  extra: Option[Json] = None)
private object ProjectBuildConfigRaw {
  implicit val decoder: Decoder[ProjectBuildConfigRaw] =
    renamedDec(
      "setVersion" -> "set-version",
      "setVersionSuffix" -> "set-version-suffix",
      "crossVersion" -> "cross-version",
      "checkMissing" -> "check-missing",
      "rewriteOverrides" -> "rewrite-overrides",
      "useJGit" -> "use-jgit")(deriveDecoder[ProjectBuildConfigRaw])
}

object ProjectBuildConfig {
  implicit val projectBuildConfigDecoder: Decoder[ProjectBuildConfig] = Decoder.instance { c =>
    c.as[ProjectBuildConfigRaw].flatMap { raw =>
      val system = raw.system.getOrElse("sbt")
      ExtraConfig.decoderFor(system) match {
        case None => Left(io.circe.DecodingFailure("Build system \"" + system + "\" is unknown.", c.history))
        case Some(dec) =>
          (raw.extra match {
            case None => Right(None)
            case Some(json) => dec.decodeJson(json).map(Some(_))
          }).map { newData =>
            ProjectBuildConfig(raw.name, system, raw.uri.getOrElse("nil"), raw.setVersion, raw.setVersionSuffix,
              raw.deps.getOrElse(Nil), raw.crossVersion map { _.s }, raw.checkMissing map { _.s },
              raw.rewriteOverrides map { _.s }, raw.useJGit, raw.space, newData)
          }
      }
    }
  }
  implicit val projectBuildConfigEncoder: Encoder[ProjectBuildConfig] =
    dropNullValues(renamedEnc(
      "setVersion" -> "set-version",
      "setVersionSuffix" -> "set-version-suffix",
      "crossVersion" -> "cross-version",
      "checkMissing" -> "check-missing",
      "rewriteOverrides" -> "rewrite-overrides",
      "useJGit" -> "use-jgit")(deriveEncoder[ProjectBuildConfig]))
}

case class DepsModifiers(
  // One or more dependencies, in the form "org#name".
  // They will not be rewired by dbuild
  ignore: SeqString = Seq.empty,
  // One or more dependencies, in the form "org#name".
  // They are simply appended to all of the subprojects.
  // These are dependencies as seen by dbuild (as extracted); they are not
  // the actual project's dependencies.
  inject: SeqString = Seq.empty)
object DepsModifiers {
  implicit val depsModifiersEncoder: Encoder[DepsModifiers] = deriveEncoder[DepsModifiers]
  implicit val depsModifiersDecoder: Decoder[DepsModifiers] = deriveDecoder[DepsModifiers]
}

/**
 * A specification for Spaces, as used by projects.
 * It can be deserialized from:
 *
 *   space: xyz
 *   space: {from: xyz, to: xyz}
 *   space: {from: xyz, to: [ xyz, zyx,... ]}
 *   space: {from: [xyz,...], to: [ xyz, zyx,... ]}
 * etc.
 *
 * The meaning of the sequences for "to" and "from" is very
 * different. The artifacts generated by the project will be
 * published to *all* the spaces in the "to" list.
 *
 *  Conversely, the dependencies will normally be looked up
 * only in the space listed as the *first* element of the
 * "from" list. SOME build systems (notably sbt) may use
 * multiple "universes" of artifacts; in that case, each
 * universe will look up for dependent artifacts in
 * subsequent elements of the "from" list.
 *
 * In order to be used, the list in "from" is converted
 * into an infinite stream. The elements that are missing
 * in "from" are replaced with the empty string, which is
 * a special space (to which one cannot publish), and which
 * means "do not rewire".
 */
case class Space(from: Seq /*Levels*/ [String], to: Seq[String]) {
  // We can't place "defaults" in the companion object, otherwise
  // the case class loses its standard facilities. So we place it here instead.
  private object SpaceDefaults {
    val defaults = "default" +: Stream.continually("")
  }
  def this(s: String) = this(Seq(s), Seq(s))
  from foreach Utils.testSpaceName
  to foreach Utils.testSpaceName
  def fromStream = from.toStream ++ SpaceDefaults.defaults.drop(from.length)
}
case class SpaceAux(from: SeqString = Seq.empty, to: SeqString = Seq("default"))
object SpaceAux {
  implicit val spaceAuxEncoder: Encoder[SpaceAux] = deriveEncoder[SpaceAux]
  implicit val spaceAuxDecoder: Decoder[SpaceAux] = deriveDecoder[SpaceAux]
}
object Space {
  implicit val spaceDecoder: Decoder[Space] = Decoder.instance { c =>
    c.value.asString match {
      case Some(s) => Right(new Space(s))
      case None => c.as[SpaceAux].map(aux => Space(aux.from, aux.to))
    }
  }
  // Note: in the original Jackson-based serializer, the "single symmetric string" shorthand
  // was gated on `value.to(0) == value.from` -- comparing a String to a Seq[String], which can
  // never be true. That branch was therefore always dead, and Space was always written out in
  // full {from:...,to:...} form. We replicate that observable behavior here (see SpaceDeserializer
  // for the reverse: reading *does* correctly accept the bare-string shorthand).
  implicit val spaceEncoder: Encoder[Space] = Encoder.instance { value =>
    SpaceAux.spaceAuxEncoder(SpaceAux(value.from, value.to))
  }
}

/**
 * The initial dbuild configuration. The "build" section is a complete
 * specification of the actual build, while the "options" section contains
 * accessory tasks and options that do not affect the actual build, but do
 * affect other parts of the dbuild behavior.
 */
case class DBuildConfiguration(
  build: SeqDBC, // auto-wrapped Seq[DBuildConfig]
  options: GeneralOptions = GeneralOptions(), // pick defaults if empty
  vars: Option[Vars] = Some(Vars()),
  /**
   * 'properties' can be one or more URIs to properties lists,
   * whose content will be merged with the configuration file, and used
   * during expansion.
   */
  properties: SeqString = Seq.empty) {
  /** The unique SHA for this configuration */
  def uuid = hashing sha1 this
}
object DBuildConfiguration {
  implicit val dBuildConfigurationEncoder: Encoder[DBuildConfiguration] = dropNullValues(deriveEncoder[DBuildConfiguration])
  implicit val dBuildConfigurationDecoder: Decoder[DBuildConfiguration] = deriveDecoder[DBuildConfiguration]
}

/* This section is unchecked, and is used prior to deserialization by
 * the Typesafe config library. Its contents are no longer used once we
 * get to deserialization, which is why it is always replaced with an
 * empty record.
 */
case class Vars()
object Vars {
  implicit val varsEncoder: Encoder[Vars] = Encoder.instance(_ => Json.obj())
  implicit val varsDecoder: Decoder[Vars] = Decoder.instance(_ => Right(Vars()))
}

/**
 *  At this time, only the ProjectBuildConfig is required; the BuildOptions
 *  have already been replaced into the corresponding project records.
 */
case class ExtractionConfig(buildConfig: ProjectBuildConfig) {
  def uuid = hashing sha1 this
  def extra[T] = buildConfig.getExtra[T]
}
object ExtractionConfig {
  implicit val extractionConfigEncoder: Encoder[ExtractionConfig] = deriveEncoder[ExtractionConfig]
  implicit val extractionConfigDecoder: Decoder[ExtractionConfig] = deriveDecoder[ExtractionConfig]
}

/**
 * The configuration for a build. Include here every bit of information that
 * affects the actual build; the parts that do not affect the actual build,
 * and do not belong into the repeatable build configuration, go into the
 * GeneralOptions class instead.
 *
 * Apart for "projects", these are options that affect all of the projects, but must not affect extraction:
 * extraction fully relies on the fact that the project is fully described by the
 * ProjectBuildConfig record. However, it may contain defaults that are used to
 * fill in the ProjectBuildConfig (like, for example, extraction-version).
 *
 * These options, however, can affect the building stage; a copy of the record is
 * included in the RepeatableDBuildConfig, and is then included in each RepeatableProjectBuild
 * obtained from the repeatableBuilds within the RepeatableDBuildConfig.
 * Therefore *ONLY* place in this section the global options that affect the repeatability of the
 * builds!! Place other global options elsewhere, in other top-level sections. Similarly, do no place
 * options that do not impact on the repeatability of the build inside the projects section; instead,
 * place them in a separate section, specifying the list of projects to which they apply (like deploy
 * and notifications).
 *
 * This section contains the option "cross-version, which controls the
 * crossVersion and scalaBinaryVersion sbt flags. It can have the following values:
 *   - "disabled" (default): All cross-version suffixes will be disabled, and each project
 *     will be published with just a dbuild-specific version suffix (unless "set-version" is used).
 *     However, the library dependencies that refer to Scala projects that are not included in this build
 *     configuration, and that have "binary" or "full" CrossVersion will have their scala version set to
 *     the full scala version string: as a result, missing dependent projects will be detected.
 *   - "standard": Each project will compile with its own suffix (typically _2.10 for 2.10.x, for example).
 *     Further, library dependencies that refer to Scala projects that are not included in this build
 *     configuration will not be rewritten: they might end up being fetched from Maven if a compatible
 *     version is found.
 *     This settings must be used when releasing, typically in conjunction with "set-version", in order
 *     to make sure cross-versioning works as it would in the original projects.
 *   - "full": Similar in concept to "disabled", except the all the sbt projects are changed so that
 *     the full Scala version string is used as a cross-version suffix (even those that would normally
 *     have cross-version disabled). Missing dependent projects will be detected.
 *   - "binaryFull": It is a bit of a hybrid between standard and full. This option will cause
 *     the projects that would normally publish with a binary suffix (like "_2.10") to publish using the
 *     full scala version string instead. The projects that have cross building disabled, however, will be
 *     unaffected. Missing dependent projects will be detected. This configuration is for testing only.
 *
 * In practice, do not include the cross-version option at all in normal use, and just
 * add "{cross-version:standard}" if you are planning to release using "set-version".
 *
 * This section also contains the sbt version that should be used by default (unless overridden in the individual
 * projects) to compile all the projects. If not specified, the string "0.12.4" is used.
 */
case class DBuildConfig(projects: Seq[ProjectBuildConfig],
  /* deprecated, see deserializer */
  options: Option[DeprecatedBuildOptions],
  crossVersion: SeqString /*Levels*/ = Seq.empty, //all missing values will be "disabled"
  // if "standard" (the default), use whatever sbt version is defined by the project. If none is defined, stop and ask for one.
  checkMissing: SeqBoolean /*Levels*/ = Seq.empty, //all missing values will be determined
  // according to the corresponding value of cross-version: if "standard", then false, else true.
  rewriteOverrides: SeqBoolean /*Levels*/ = Seq.empty, //all missing values will be true
  sbtVersion: String = "standard",
  // This option applies to all sbt-based projects, unless overridden.
  // see SbtExtraConfig for details.
  extractionVersion: String = "standard",
  // Select jgit rather than the command-line git. It is in the BuildOptions,
  // rather than in the GeneralOptions, as its value may conceivably have
  // an effect on building (for instance due to a difference in checkout because
  // of an implementation bug)
  useJGit: Boolean = false,
  // settings for sbt-based builds
  // Note on the default value: it must contain a single empty SeqString. Using as a
  // default value an empty Seq[SeqString] will result in the value obtained after
  // serialization and deserialization to be different, which causes troubles when
  // checking repository consistency.
  sbtSettings: SeqSeqString /* Levels */ = SeqSeqString(Seq(Seq.empty)),
  // commands for sbt-based builds
  sbtCommands: SeqString = Seq.empty,
  // commands for sbt-based builds, to be run after compilation and test
  sbtPostCommands: SeqString = Seq.empty,
  // see javaOptions in SbtExtraConfig
  sbtJavaOptions: Option[SeqString] = None,
  // Default space for regular project
  space: Space = new Space("default")) extends BuildOptions
object DBuildConfig {
  private val renames: Seq[(String, String)] = Seq(
    "crossVersion" -> "cross-version",
    "checkMissing" -> "check-missing",
    "rewriteOverrides" -> "rewrite-overrides",
    "sbtVersion" -> "sbt-version",
    "extractionVersion" -> "extraction-version",
    "useJGit" -> "use-jgit",
    "sbtSettings" -> "sbt-settings",
    "sbtCommands" -> "sbt-commands",
    "sbtPostCommands" -> "sbt-post-commands",
    "sbtJavaOptions" -> "sbt-java-options")
  implicit val dBuildConfigEncoder: Encoder[DBuildConfig] = dropNullValues(renamedEnc(renames: _*)(deriveEncoder[DBuildConfig]))
  implicit val dBuildConfigDecoder: Decoder[DBuildConfig] = renamedDec(renames: _*)(deriveDecoder[DBuildConfig])
}

/**
 * General options for dbuild, that do not affect the actual build.
 */
case class GeneralOptions(deploy: Seq[DeployOptions] = Seq.empty,
  notifications: NotificationOptions = NotificationOptions(),
  compare: Seq[ComparisonOptions] = Seq(),
  resolvers: Map[String, String] = Map[String, String](),
  cleanup: CleanupOptions = CleanupOptions(),
  timeouts: Timeouts = Timeouts()
)
object GeneralOptions {
  implicit val generalOptionsEncoder: Encoder[GeneralOptions] = deriveEncoder[GeneralOptions]
  implicit val generalOptionsDecoder: Decoder[GeneralOptions] = deriveDecoder[GeneralOptions]
}

// expiration times are in hours. Dirs are cleaned if the (truncated)
// number of hours between now and the time in which a build was last
// attempted (the initial time) is >= than the number specified here
case class CleanupExpirations(success: Int, failure: Int)
object CleanupExpirations {
  implicit val cleanupExpirationsEncoder: Encoder[CleanupExpirations] = deriveEncoder[CleanupExpirations]
  implicit val cleanupExpirationsDecoder: Decoder[CleanupExpirations] = deriveDecoder[CleanupExpirations]
}
/**
 * The default maximum ages before reclaiming disk space are:
 * - successful build: 2 days
 * - failed build: 7 days
 * - successful extraction: 5 days
 * - failed extraction: 7 days
 */
case class CleanupOptions(
  build: CleanupExpirations = CleanupExpirations(success = 48, failure = 168),
  extraction: CleanupExpirations = CleanupExpirations(success = 120, failure = 168))
object CleanupOptions {
  implicit val cleanupOptionsEncoder: Encoder[CleanupOptions] = deriveEncoder[CleanupOptions]
  implicit val cleanupOptionsDecoder: Decoder[CleanupOptions] = deriveDecoder[CleanupOptions]
}

/**
 * This class acts as a useful wrapper for parameters that are Seqs of Strings: it makes it
 * possible to specify a simple string whenever an array of strings is expected in the JSON file.
 * Quite handy, really.
 */
case class SeqString(override val s: Seq[String]) extends Flex[String](s) {
  // whenever I use a SeqString to apply map or foreach, the implicit
  // will kick in. However, when I try to print or use it as a string,
  // its method toString() will be called. This is not normally a problem
  // (we don't usually print Seq[String]s directly in user-facing code),
  // but, just in case:
  override def toString() = s.toString
}
object SeqString {
  implicit val seqStringEncoder: Encoder[SeqString] = flexEncoder[String](Encoder.encodeString).contramap(_.s)
  implicit val seqStringDecoder: Decoder[SeqString] = flexDecoder[String](Decoder.decodeString).map(SeqString(_))
}
object SeqStringH {
  implicit def SeqToSeqString(s: Seq[String]): SeqString = SeqString(s)
  implicit def SeqStringToSeq(a: SeqString): Seq[String] = a.s
}

/**
 * Similar to the above, but for Booleans.
 */
case class SeqBoolean(override val s: Seq[Boolean]) extends Flex[Boolean](s) {
  override def toString() = s.toString
}
object SeqBoolean {
  implicit val seqBooleanEncoder: Encoder[SeqBoolean] = flexEncoder[Boolean](Encoder.encodeBoolean).contramap(_.s)
  implicit val seqBooleanDecoder: Decoder[SeqBoolean] = flexDecoder[Boolean](Decoder.decodeBoolean).map(SeqBoolean(_))
}
object SeqBooleanH {
  implicit def SeqToSeqBoolean(s: Seq[Boolean]): SeqBoolean = SeqBoolean(s)
  implicit def SeqBooleanToSeq(a: SeqBoolean): Seq[Boolean] = a.s
}

/**
 * Similar to the above, but for DBuildConfig elements:
 * a single one in the config file will automatically be turned into an array.
 */
case class SeqDBC(override val s: Seq[DBuildConfig]) extends Flex[DBuildConfig](s)
object SeqDBC {
  implicit val seqDBCEncoder: Encoder[SeqDBC] = flexEncoder[DBuildConfig](DBuildConfig.dBuildConfigEncoder).contramap(_.s)
  implicit val seqDBCDecoder: Decoder[SeqDBC] = flexDecoder[DBuildConfig](DBuildConfig.dBuildConfigDecoder).map(SeqDBC(_))
}
object SeqDBCH {
  implicit def SeqToSeqDBC(s: Seq[DBuildConfig]): SeqDBC = SeqDBC(s)
  implicit def SeqDBCToSeq(a: SeqDBC): Seq[DBuildConfig] = a.s
}

/**
 * For DepsModifiers, we can have one modifier per level, for sbt specifically.
 */
case class SeqDepsModifiers(override val s: Seq[DepsModifiers]) extends Flex[DepsModifiers](s)
object SeqDepsModifiers {
  implicit val seqDepsModifiersEncoder: Encoder[SeqDepsModifiers] = flexEncoder[DepsModifiers](DepsModifiers.depsModifiersEncoder).contramap(_.s)
  implicit val seqDepsModifiersDecoder: Decoder[SeqDepsModifiers] = flexDecoder[DepsModifiers](DepsModifiers.depsModifiersDecoder).map(SeqDepsModifiers(_))
}
object SeqDepsModifiersH {
  implicit def SeqToSeqDM(s: Seq[DepsModifiers]): SeqDepsModifiers = SeqDepsModifiers(s)
  implicit def SeqDMToSeq(a: SeqDepsModifiers): Seq[DepsModifiers] = a.s
  implicit def OptToSeqDM(o: Option[DepsModifiers]): SeqDepsModifiers = SeqDepsModifiers(o.toSeq)
}

/**
 * We can use a SeqSeqString when we would like to supply either a single String or an
 * array of Strings (which becomes a Seq containing one element, which is a Seq[String]),
 * or directly an array of array of Strings (which becomes directly a Seq(Seq())).
 * It is only used by sbtSettings, currently.
 */
case class SeqSeqString(override val s: Seq[SeqString]) extends Flex[SeqString](s) {
  // turn the SeqSeqString into a Seq[Seq[String]]
  def expand = s map {_.s}
}
object SeqSeqStringH {
  implicit def SeqToSeqSeqString(s: Seq[SeqString]): SeqSeqString = SeqSeqString(s)
  implicit def SeqSeqStringToSeq(a: SeqSeqString): Seq[SeqString] = a.s
}

// Note: it is not enough to encode/decode as a plain Flex[SeqString].
// For instance, in the case in which the object to be deserialized
// is SeqSeqString(List(List(aaa), List(bbb))), just deserializing
// using Flex would lead to a List of two SeqString, which would
// then automatically deserialized to single strings. The result
// is a ["aaa","bbb"] in the deserialized form, which is then
// re-serialized as List(List(aaa,bbb)).
// To avoid that, in the case in which the SeqSeqString contains
// more than one element, we skip the SeqString and convert it
// directly to a Seq[Seq[String]], which is then deserialized
// into the JSON representation [["aaa"],["bbb"]].
object SeqSeqString {
  implicit val seqSeqStringEncoder: Encoder[SeqSeqString] = Encoder.instance { value =>
    value.s.length match {
      case 1 => SeqString.seqStringEncoder(value.s(0))
      case _ => Json.arr(value.s.map(ss => flexEncoder[String](Encoder.encodeString)(ss.s)): _*)
    }
  }
  // Flex cannot cope with the special case of SeqSeqString deserialization; we write a custom one.
  implicit val seqSeqStringDecoder: Decoder[SeqSeqString] = Decoder.instance { c =>
    c.value.asArray match {
      case Some(arr) =>
        // If generic is already an array of arrays, do not wrap, else wrap;
        // since we might have stuff like [ x, [ y,z ]], we need to traverse
        // the whole generic to look for arrays of arrays.
        val needsWrapping = !arr.exists(_.isArray)
        if (needsWrapping) {
          c.as[SeqString].map(ss => SeqSeqString(Seq(ss)))
        } else {
          arr.foldLeft(Right(Vector.empty): Decoder.Result[Vector[SeqString]]) { (acc, j) =>
            for { v <- acc; s <- flexDecoder[String](Decoder.decodeString).decodeJson(j) } yield v :+ SeqString(s)
          }.map(v => SeqSeqString(v.toSeq))
        }
      case None =>
        c.as[SeqString].map(ss => SeqSeqString(Seq(ss)))
    }
  }
}

/**
 * The generic auto-wrapping magic
 */
class Flex[T](val s: Seq[T])

/** Deploy information. */
case class DeployOptions(
  /** deploy target */
  uri: String,
  /** path to the credentials file */
  credentials: Option[String],
  /** names of the projects that should be deployed. Default: ".", meaning all */
  projects: SeqSelectorElement = Seq(SelectorProject(".")),
  /** signing options */
  sign: Option[DeploySignOptions],
  /** index generation options */
  index: Option[IndexOptions]) extends DeployTarget
object DeployOptions {
  implicit val deployOptionsEncoder: Encoder[DeployOptions] = dropNullValues(deriveEncoder[DeployOptions])
  implicit val deployOptionsDecoder: Decoder[DeployOptions] = deriveDecoder[DeployOptions]
}
/** used to select subprojects from one project */
case class SubProjects(from: String, subprojects: SeqString)
object SubProjects {
  implicit val subProjectsEncoder: Encoder[SubProjects] = deriveEncoder[SubProjects]
  implicit val subProjectsDecoder: Decoder[SubProjects] = deriveDecoder[SubProjects]
}

/**
 * Signing options.
 *  secret-ring is the path to the file containing the pgp secret key ring. If not supplied, '~/.gnupg/secring.gpg' is used.
 *  id is the long key id (the whole 64 bits). If not supplied, the default master key is used.
 *  passphrase is the path to the file containing the passphrase; there is no interactive option.
 */
case class DeploySignOptions(
  secretRing: Option[String],
  id: Option[String],
  passphrase: String)
object DeploySignOptions {
  implicit val deploySignOptionsEncoder: Encoder[DeploySignOptions] =
    dropNullValues(renamedEnc("secretRing" -> "secret-ring")(deriveEncoder[DeploySignOptions]))
  implicit val deploySignOptionsDecoder: Decoder[DeploySignOptions] =
    renamedDec("secretRing" -> "secret-ring")(deriveDecoder[DeploySignOptions])
}

/**
 * Index generation options.
 * This set of options is handled by the Deploy task, and assumes that the set of
 * projects/subprojects is the same as those published to the repository.
 * However, the uri/credentials are different, as the index file may be deployed elsewhere.
 */
case class IndexOptions(
  /**
   * index publication target. This uri must refer to the path, but not include the
   * file name, which is specified separately.
   */
  uri: String,
  /** path to the credentials file */
  credentials: Option[String],
  filename: String) extends DeployTarget
object IndexOptions {
  implicit val indexOptionsEncoder: Encoder[IndexOptions] = dropNullValues(deriveEncoder[IndexOptions])
  implicit val indexOptionsDecoder: Decoder[IndexOptions] = deriveDecoder[IndexOptions]
}

/** Comparison information. */
case class ComparisonOptions(
  a: SeqSelectorElement = Seq(),
  b: SeqSelectorElement = Seq(),
  skip: SeqString = Seq()) // skip is a sequence of regex patterns,
// files inside the jars whose name match them will not be compared.
object ComparisonOptions {
  implicit val comparisonOptionsEncoder: Encoder[ComparisonOptions] = deriveEncoder[ComparisonOptions]
  implicit val comparisonOptionsDecoder: Decoder[ComparisonOptions] = deriveDecoder[ComparisonOptions]
}

/**
 * Configuration used for SBT and other builds.
 */
class ExtraConfig

object ExtraConfig {
  private val decoders: Map[String, Decoder[? <: ExtraConfig]] = Map(
    "sbt" -> Decoder[SbtExtraConfig],
    "scala" -> Decoder[ScalaExtraConfig],
    "ivy" -> Decoder[IvyExtraConfig],
    "assemble" -> Decoder[AssembleExtraConfig],
    "aether" -> Decoder[AetherExtraConfig],
    "test" -> Decoder[TestExtraConfig],
    "nil" -> Decoder[NilExtraConfig])
  def decoderFor(system: String): Option[Decoder[ExtraConfig]] =
    decoders.get(system).map(_.asInstanceOf[Decoder[ExtraConfig]])

  implicit val extraConfigEncoder: Encoder[ExtraConfig] = Encoder.instance {
    case e: SbtExtraConfig      => ExtraConfigEncoders.sbtExtraConfigEncoder(e)
    case e: ScalaExtraConfig    => ExtraConfigEncoders.scalaExtraConfigEncoder(e)
    case e: IvyExtraConfig      => ExtraConfigEncoders.ivyExtraConfigEncoder(e)
    case e: AssembleExtraConfig => ExtraConfigEncoders.assembleExtraConfigEncoder(e)
    case e: AetherExtraConfig   => ExtraConfigEncoders.aetherExtraConfigEncoder(e)
    case e: TestExtraConfig     => ExtraConfigEncoders.testExtraConfigEncoder(e)
    case e: NilExtraConfig      => ExtraConfigEncoders.nilExtraConfigEncoder(e)
    case other => throw new Exception("Internal error while serializing build system config. Please report. (" + other.getClass + ")")
  }
}

/**
 * The 'extra' options for the Scala build system are:
 * build-number:  Overwrites the standard build.number, with a custom number
 *                Note that set-version changes the jar artifact version number,
 *                while build-number changes the version that Scala reports, for
 *                example, in the REPL.
 * build-target:  Overrides the standard ant target that is invoked in order to
 *                generate the artifacts. The default is 'distpack-maven-opt', and it
 *                is not normally changed.
 * deploy-target: Overrides the ant target that is invoked in order to
 *                copy the artifacts to a local repository. The default is
 *                'deploy.local'.
 * build-options: A sequence of additional options that will be passed to ant.
 *                They can specify properties, or modify in some other way the
 *                build. These options will be passed after the ones set by
 *                dbuild, on the command line.
 * All fields are optional.
 */
case class ScalaExtraConfig(
  buildNumber: Option[BuildNumber],
  // deploy-target and build-target have been replaced by "targets"
  buildTarget: Option[String],
  deployTarget: Option[String],
  // TODO: eventually remove the two old options above
  // "targets" is a list of pairs, where the first component is the target
  // and the second is the path relative to the root where the target should be run
  targets: Seq[(String, String)] = Seq.empty,
  buildOptions: SeqString = Seq.empty,
  exclude: SeqString = Seq.empty // if empty -> exclude no projects (default)
  ) extends ExtraConfig

case class BuildNumber(major: String, minor: String, patch: String, bnum: String)
object BuildNumber {
  implicit val buildNumberEncoder: Encoder[BuildNumber] = deriveEncoder[BuildNumber]
  implicit val buildNumberDecoder: Decoder[BuildNumber] = deriveDecoder[BuildNumber]
}

case class IvyExtraConfig(
  sources: Boolean = false,
  javadoc: Boolean = false,
  mainJar: Boolean = true,
  artifacts: Seq[IvyArtifact] = Seq.empty,
  // The snapshot marker is used internally by the Ivy build system
  // in order to distinguish among different snapshots of the same
  // dependency, in which case it contains the publication date.
  // Note: this field is not for use by end user.
  snapshotMarker: Option[String]) extends ExtraConfig

case class AetherExtraConfig(
  sources: Boolean = false,
  javadoc: Boolean = false,
  mainJar: Boolean = true,
  snapshotMarker: Option[String]) extends ExtraConfig

case class IvyArtifact(
  classifier: String = "",
  typ: String = "jar",
  ext: String = "jar",
  configs: SeqString = Seq("default"))
object IvyArtifact {
  implicit val ivyArtifactEncoder: Encoder[IvyArtifact] = renamedEnc("typ" -> "type")(deriveEncoder[IvyArtifact])
  implicit val ivyArtifactDecoder: Decoder[IvyArtifact] = renamedDec("typ" -> "type")(deriveDecoder[IvyArtifact])
}

/**
 * sbt-specific build parameters
 */
case class SbtExtraConfig(
  // None is interpreted as default: use build.sbt-version
  sbtVersion: Option[String] = None,
  directory: String = "",
  runTests: Boolean = true,
  skipMissingTests: Boolean = false,
  testTasks: SeqString = Seq("test"),
  // For the difference between the build section's "javaOptions",
  // the project-specific "options", and javaAllOptions (below),
  // please refer to SbtBuildSystem.expandExtra()
  options: SeqString = Seq.empty,
  javaAllOptions: SeqString = Seq.empty,
  // before rewiring, append these settings
  settings: SeqSeqString = SeqSeqString(Seq(Seq.empty)), /*Levels*/
  // before building, run these commands ("set" or others)
  commands: SeqString = Seq.empty,
  // after building and testing, run these commands
  postCommands: SeqString = Seq.empty,
  projects: SeqString = Seq.empty, // if empty -> build all projects (default)
  exclude: SeqString = Seq.empty, // if empty -> exclude no projects (default)
  /**
   *  Use "standard" to use the project's standard Scala compiler for extraction,
   *  or a version string to force a different Scala compiler.
   */
  // None is interpreted as default: use build.extraction-version
  extractionVersion: Option[String] = None) extends ExtraConfig

private object ExtraConfigEncoders {
  val scalaExtraConfigRenames: Seq[(String, String)] = Seq(
    "buildNumber" -> "build-number", "buildTarget" -> "build-target",
    "deployTarget" -> "deploy-target", "buildOptions" -> "build-options")
  implicit val scalaExtraConfigEncoder: Encoder[ScalaExtraConfig] =
    dropNullValues(renamedEnc(scalaExtraConfigRenames: _*)(deriveEncoder[ScalaExtraConfig]))
  implicit val scalaExtraConfigDecoder: Decoder[ScalaExtraConfig] =
    renamedDec(scalaExtraConfigRenames: _*)(deriveDecoder[ScalaExtraConfig])

  implicit val ivyExtraConfigEncoder: Encoder[IvyExtraConfig] =
    dropNullValues(renamedEnc("mainJar" -> "main-jar", "snapshotMarker" -> "snapshot-marker")(deriveEncoder[IvyExtraConfig]))
  implicit val ivyExtraConfigDecoder: Decoder[IvyExtraConfig] =
    renamedDec("mainJar" -> "main-jar", "snapshotMarker" -> "snapshot-marker")(deriveDecoder[IvyExtraConfig])

  implicit val aetherExtraConfigEncoder: Encoder[AetherExtraConfig] =
    dropNullValues(renamedEnc("mainJar" -> "main-jar", "snapshotMarker" -> "snapshot-marker")(deriveEncoder[AetherExtraConfig]))
  implicit val aetherExtraConfigDecoder: Decoder[AetherExtraConfig] =
    renamedDec("mainJar" -> "main-jar", "snapshotMarker" -> "snapshot-marker")(deriveDecoder[AetherExtraConfig])

  val sbtExtraConfigRenames: Seq[(String, String)] = Seq(
    "sbtVersion" -> "sbt-version", "runTests" -> "run-tests",
    "skipMissingTests" -> "skip-missing-tests", "testTasks" -> "test-tasks",
    "javaAllOptions" -> "project-specific-all-java-options-combined",
    "postCommands" -> "post-commands", "extractionVersion" -> "extraction-version")
  implicit val sbtExtraConfigEncoder: Encoder[SbtExtraConfig] =
    dropNullValues(renamedEnc(sbtExtraConfigRenames: _*)(deriveEncoder[SbtExtraConfig]))
  implicit val sbtExtraConfigDecoder: Decoder[SbtExtraConfig] =
    renamedDec(sbtExtraConfigRenames: _*)(deriveDecoder[SbtExtraConfig])

  implicit val nilExtraConfigEncoder: Encoder[NilExtraConfig] = deriveEncoder[NilExtraConfig]
  implicit val nilExtraConfigDecoder: Decoder[NilExtraConfig] = deriveDecoder[NilExtraConfig]
  implicit val testExtraConfigEncoder: Encoder[TestExtraConfig] = deriveEncoder[TestExtraConfig]
  implicit val testExtraConfigDecoder: Decoder[TestExtraConfig] = deriveDecoder[TestExtraConfig]
  implicit val assembleExtraConfigEncoder: Encoder[AssembleExtraConfig] = deriveEncoder[AssembleExtraConfig]
  implicit val assembleExtraConfigDecoder: Decoder[AssembleExtraConfig] = deriveDecoder[AssembleExtraConfig]
}
import ExtraConfigEncoders.*

object ScalaExtraConfig {
  implicit val scalaExtraConfigEncoder: Encoder[ScalaExtraConfig] = ExtraConfigEncoders.scalaExtraConfigEncoder
  implicit val scalaExtraConfigDecoder: Decoder[ScalaExtraConfig] = ExtraConfigEncoders.scalaExtraConfigDecoder
}
object IvyExtraConfig {
  implicit val ivyExtraConfigEncoder: Encoder[IvyExtraConfig] = ExtraConfigEncoders.ivyExtraConfigEncoder
  implicit val ivyExtraConfigDecoder: Decoder[IvyExtraConfig] = ExtraConfigEncoders.ivyExtraConfigDecoder
}
object AetherExtraConfig {
  implicit val aetherExtraConfigEncoder: Encoder[AetherExtraConfig] = ExtraConfigEncoders.aetherExtraConfigEncoder
  implicit val aetherExtraConfigDecoder: Decoder[AetherExtraConfig] = ExtraConfigEncoders.aetherExtraConfigDecoder
}
object SbtExtraConfig {
  implicit val sbtExtraConfigEncoder: Encoder[SbtExtraConfig] = ExtraConfigEncoders.sbtExtraConfigEncoder
  implicit val sbtExtraConfigDecoder: Decoder[SbtExtraConfig] = ExtraConfigEncoders.sbtExtraConfigDecoder
}

object BuildSystemExtras {
  val buildSystems: Map[String, java.lang.Class[? <: ExtraConfig]] = Map(
    "sbt" -> classOf[SbtExtraConfig],
    "scala" -> classOf[ScalaExtraConfig],
    "ivy" -> classOf[IvyExtraConfig],
    "assemble" -> classOf[AssembleExtraConfig],
    "aether" -> classOf[AetherExtraConfig],
    "test" -> classOf[TestExtraConfig],
    "nil" -> classOf[NilExtraConfig])
}

/** configuration for the Nil build system */
case class NilExtraConfig() extends ExtraConfig
object NilExtraConfig {
  implicit val nilExtraConfigEncoder: Encoder[NilExtraConfig] = ExtraConfigEncoders.nilExtraConfigEncoder
  implicit val nilExtraConfigDecoder: Decoder[NilExtraConfig] = ExtraConfigEncoders.nilExtraConfigDecoder
}

/** configuration for the Test build system */
case class TestExtraConfig() extends ExtraConfig
object TestExtraConfig {
  implicit val testExtraConfigEncoder: Encoder[TestExtraConfig] = ExtraConfigEncoders.testExtraConfigEncoder
  implicit val testExtraConfigDecoder: Decoder[TestExtraConfig] = ExtraConfigEncoders.testExtraConfigDecoder
}

/** configuration for the Assemble build system */
case class AssembleExtraConfig(
  parts: SeqDBC = Seq()) extends ExtraConfig
object AssembleExtraConfig {
  implicit val assembleExtraConfigEncoder: Encoder[AssembleExtraConfig] = ExtraConfigEncoders.assembleExtraConfigEncoder
  implicit val assembleExtraConfigDecoder: Decoder[AssembleExtraConfig] = ExtraConfigEncoders.assembleExtraConfigDecoder
}

// our simplified version of Either: we use it to group String and SelectorSubProjects in a transparent manner
sealed abstract class SelectorElement { def name: String }
case class SelectorProject(a: String) extends SelectorElement {
  override def toString() = a
  def name = a
}
case class SelectorSubProjects(info: SubProjects) extends SelectorElement {
  override def toString() = info.from + " " + info.subprojects.mkString("(", ",", ")")
  def name = info.from
}

object SelectorElement {
  implicit val selectorElementEncoder: Encoder[SelectorElement] = Encoder.instance {
    case SelectorProject(s) => Json.fromString(s)
    case SelectorSubProjects(d) => SubProjects.subProjectsEncoder(d)
  }
  implicit val selectorElementDecoder: Decoder[SelectorElement] = Decoder.instance { c =>
    c.value.asString match {
      case Some(s) => Right(SelectorProject(s))
      case None =>
        // We have renamed "publish" to "subprojects", which is a bit more generic;
        // we can use SelectorElements in more contexts. So, in order to assist with
        // the migration, let's see if a "publish" field was encountered.
        c.value.asObject match {
          case Some(obj) if obj.contains("publish") =>
            Left(io.circe.DecodingFailure(
              "In the subproject selection, the field \"publish\" is now called \"subprojects\": please update your build file.", c.history))
          case _ => c.as[SubProjects].map(SelectorSubProjects(_))
        }
    }
  }
}

/**
 * same as SeqString, for Seq[SelectorElement]: a lonely String or a lonely
 *  SelectorSubProjs can also be used when a Seq[SelectorElement] is requested.
 */
case class SeqSelectorElement(override val s: Seq[SelectorElement]) extends Flex[SelectorElement](s) {
  /**
   * From its list of selected projects, which may include '.' for the root, and
   *  the BuildOutcome of the root, flattens the definition in order to select a
   *  subset of the root children.
   *  If '.' is present as a project, return the list of all the children.
   *  If '.' is present in a subproject definition, consider the list of
   *  subprojects as children (they are the subprojects of root, in a sense).
   *  Combine that list with the of remaining requested projects. If multiple
   *  project/subproject requests exist for the same project name, combine them together.
   *  NOTE: there is no assumption that the project names in the various request actually exist.
   *
   *  An auxiliary role of this method is that of performing a sanity check on
   *  the list of projects/subprojects, which is directly the list that
   *  the user wrote in the configuration file, and may contain errors.
   */
  def flattenAndCheckProjectList(allProjNames: Set[String]): Set[SelectorElement] = {
    def reqFromNames(n: Set[String]): Set[SelectorElement] = n map SelectorProject
    // let's split the requests by type
    val projReqs = s.collect { case p: SelectorProject => p }.toSet
    val subProjReqs = s.collect { case p: SelectorSubProjects => p }.toSet

    val fromRoot = if (projReqs.exists(_.name == ".")) allProjNames else Set[String]()
    // list of names of projects mentioned in subprojects from root
    val fromDotSubs = subProjReqs.filter(_.name == ".").flatMap { (p: SelectorSubProjects) => p.info.subprojects }
    // are you kidding me?
    if (fromDotSubs.contains(".")) sys.error("A from/publish defined '.' as a subproject of '.', which is impossible. Please amend.")
    // ok, this is the complete list of full project requests
    val allProjReqs: Set[SelectorElement] = reqFromNames(fromRoot) ++ reqFromNames(fromDotSubs) ++ projReqs.filterNot(_.name == ".")
    // remove the subproj requests that are already in the full proj set.
    val restSubProjReqs = subProjReqs.filterNot { p => allProjReqs.map { _.name }.contains(p.name) }
    // and now we flatten together those with the same 'from'
    val allSubProjReqsMap = restSubProjReqs.filterNot(_.name == ".").groupBy(_.name).toSet
    val allSubProjReq: Set[SelectorElement] = allSubProjReqsMap map {
      case (name, seq) => SelectorSubProjects(SubProjects(name, seq.map { _.info.subprojects }.flatten.toSeq))
    }
    val reqs = allSubProjReq ++ allProjReqs
    val unknown = reqs.map(_.name).diff(allProjNames)
    if (unknown.nonEmpty) sys.error(unknown.mkString("These project names are unknown: ", ",", ""))
    reqs
  }
}
object SeqSelectorElement {
  implicit val seqSelectorElementEncoder: Encoder[SeqSelectorElement] =
    flexEncoder[SelectorElement](SelectorElement.selectorElementEncoder).contramap(_.s)
  implicit val seqSelectorElementDecoder: Decoder[SeqSelectorElement] =
    flexDecoder[SelectorElement](SelectorElement.selectorElementDecoder).map(SeqSelectorElement(_))
}
object SeqSelectorElementH {
  implicit def SeqToSeqSelectorElement(s: Seq[SelectorElement]): SeqSelectorElement = SeqSelectorElement(s)
  implicit def SeqSelectorElementToSeq(a: SeqSelectorElement): Seq[SelectorElement] = a.s
}
/**
 * same as SeqString, for Seq[Notification]: a single Notification will be wrapped into an array.
 */
case class SeqNotification(override val s: Seq[Notification]) extends Flex[Notification](s)
object SeqNotification {
  implicit val seqNotificationEncoder: Encoder[SeqNotification] =
    flexEncoder[Notification](Notification.notificationEncoder).contramap(_.s)
  implicit val seqNotificationDecoder: Decoder[SeqNotification] =
    flexDecoder[Notification](Notification.notificationDecoder).map(SeqNotification(_))
}
object SeqNotificationH {
  implicit def SeqToSeqNotification(s: Seq[Notification]): SeqNotification = SeqNotification(s)
  implicit def SeqNotificationToSeq(a: SeqNotification): Seq[Notification] = a.s
}

/** see DBuildConfig for details. */
trait ExtraOptions {
  def sbtVersion: String
  def extractionVersion: String
  def sbtSettings: SeqSeqString /*Levels*/
  def sbtCommands: SeqString
  def sbtPostCommands: SeqString
  def sbtJavaOptions: Option[SeqString]
}
trait ProjectOptions {
  def crossVersion: SeqString /*Levels*/
  def checkMissing: SeqBoolean /*Levels*/
  def rewriteOverrides: SeqBoolean /*Levels*/
  def useJGit: Boolean
  def space: Space
}
abstract class BuildOptions extends ExtraOptions with ProjectOptions

abstract class DeprecatedBuildOptions
object DeprecatedBuildOptions {
  implicit val deprecatedBuildOptionsDecoder: Decoder[DeprecatedBuildOptions] = Decoder.instance { c =>
    sys.error("\"build.options\" have moved. Please rename \"build.options.xxx\" to just \"build.xxx\".")
  }
  // Never actually holds an instance (the field is always None in practice, since the decoder above
  // always throws instead of ever producing one); only needed so Encoder[DBuildConfig] can be derived.
  implicit val deprecatedBuildOptionsEncoder: Encoder[DeprecatedBuildOptions] = Encoder.instance(_ => Json.Null)
}

/**
 * This section is used to notify users, by using some notification system.
 */
case class NotificationOptions(
  templates: Seq[NotificationTemplate] = Seq.empty,
  send: SeqNotification = Seq(Notification(kind = "console", send = None, when = Seq("always"))),
  /**
   * This section optionally contains defaults to be used for the various notification kinds.
   *  The values specified in the defaults section will be used for that kind if no value
   *  has been specified in a "send" record of that kind. Since the defaults of the defaults are
   *  by default the defaults of the notifications (since they use the same Notification record),
   *  unspecified fields in the defaults cause no change to the default interpretation of send
   *  records.
   */
  default: SeqNotification = Seq[Notification]())
object NotificationOptions {
  implicit val notificationOptionsEncoder: Encoder[NotificationOptions] = deriveEncoder[NotificationOptions]
  implicit val notificationOptionsDecoder: Decoder[NotificationOptions] = deriveDecoder[NotificationOptions]
}
/**
 *  A notification template; for notification systems that require short messages,
 *  use only the subject line. It is a template because variable
 *  substitution may occur before printing.
 *  It can have three components:
 *  1) A summary (<50 characters), with a short message of what went wrong.
 *     It is required, and is suitable, for instance, for a short console report
 *     or as an email subject line.
 *  2) A slightly longer short summary (<110 characters), suitable for SMS, Tweets, etc.
 *     It should be self-contained in terms of information. Defaults to the short summary.
 *  3) A long body with a more complete description. Defaults to the short message.
 *  Do not terminate it with a \n, as one will be added by the notification system if
 *  required in that specific case.
 *  An Id is also present, and is used to match against the (optional) template
 *  requested in the notification.
 */
case class NotificationTemplate(
  id: String,
  summary: String,
  short: Option[String] = None,
  long: Option[String] = None)
object NotificationTemplate {
  implicit val notificationTemplateEncoder: Encoder[NotificationTemplate] = dropNullValues(deriveEncoder[NotificationTemplate])
  implicit val notificationTemplateDecoder: Decoder[NotificationTemplate] = deriveDecoder[NotificationTemplate]
}

/**
 * The NotificationTemplate is first resolved against the notification,
 * obtaining a ResolvedTemplate, then expanded by a formatter, obtaining
 * a TemplateFormatter, which can be used by the send() routine.
 */
case class ResolvedTemplate(
  id: String,
  summary: String,
  short: String,
  long: String)
object ResolvedTemplate {
  implicit val resolvedTemplateEncoder: Encoder[ResolvedTemplate] = deriveEncoder[ResolvedTemplate]
  implicit val resolvedTemplateDecoder: Decoder[ResolvedTemplate] = deriveDecoder[ResolvedTemplate]
}

case class Notification(
  /** the kind of notification. Default is "email" */
  kind: String = "email",
  /**
   * kind-specific arguments. Optional, but some
   *  notification kinds (notably email) may require it.
   */
  send: Option[NotificationKind],
  /**
   * One of these IDs must match one of the BuildOutcome
   *  IDs for the notification to be sent. The default is
   *  when = [bad,success], which will send a message on every
   *  failure, and on the first success whenever there
   *  is a change in code or dependencies.
   */
  when: SeqString = Seq("bad", "success"),
  /** if None, default to the one from the outcome */
  template: Option[String] = None,
  /**
   * Names of the projects relevant to this notification.
   *  Default: ".", meaning that a notification will be
   *  sent with the status for the root build. If multiple
   *  projects are listed, a report will be sent for each
   *  of the projects (to the same recipient) (if the 'when'
   *  selector applies); that may be of use if a single recipient
   *  is used for two or more projects that do not depend on
   *  one another.
   *  dbuild is able to build a list automatically
   *  if a single string is specified.
   */
  projects: SeqSelectorElement = Seq(SelectorProject("."))) {
  /**
   * If the notification refers to a specific template name, use that template name,
   * otherwise the template name we need is the same as the notification kind.
   * Once we determined that, we search for it in the list of templates, passed as an
   * argument. Once we find it, we replace the missing parts of the template using
   * the appropriate defaults, and return the resolved template.
   */
  def resolveTemplate(definedTemplates: Seq[NotificationTemplate]): ResolvedTemplate = {
    val templName = template match {
      case None => kind
      case Some(t) => t
    }
    val templ = definedTemplates.find(_.id == templName) getOrElse sys.error("The requested notification template \"" + templName + "\" was not found.")
    val short = templ.short match {
      case Some(s) => s
      case None => templ.summary
    }
    val long = templ.long match {
      case Some(l) => l
      case None => short
    }
    ResolvedTemplate(templ.id, templ.summary, short, long)
  }
}
// We need this shadow class for deserialization to work: "send" needs to be decoded
// according to the sibling "kind" field, so we decode it as raw JSON first.
private case class NotificationRaw(
  kind: String = "email",
  send: Option[Json] = None,
  when: SeqString = Seq("bad", "success"),
  template: Option[String] = None,
  projects: SeqSelectorElement = Seq(SelectorProject(".")))
private object NotificationRaw {
  implicit val decoder: Decoder[NotificationRaw] = deriveDecoder[NotificationRaw]
}
object Notification {
  implicit val notificationEncoder: Encoder[Notification] = dropNullValues(deriveEncoder[Notification])
  implicit val notificationDecoder: Decoder[Notification] = Decoder.instance { c =>
    c.as[NotificationRaw].flatMap { raw =>
      val kind = raw.kind
      NotificationKind.decoderFor(kind) match {
        case None => Left(io.circe.DecodingFailure("Notification kind \"" + kind + "\" is unknown.", c.history))
        case Some(dec) =>
          (raw.send match {
            case None => Right(None)
            case Some(json) => dec.decodeJson(json).map(Some(_))
          }).map { newData =>
            Notification(kind, newData, raw.when, raw.template, raw.projects)
          }
      }
    }
  }
}

/**
 * The descriptor of options for each notification mechanism;
 * subclasses are ConsoleNotification, EmailNotification, etc.
 * All the implementing notification kinds should have a
 * nullary constructor, in order to allow for "default"
 * notifications; any inappropriate default value should be
 * detected when sending (or before).
 */
abstract class NotificationKind

object NotificationKind {
  private val decoders: Map[String, Decoder[? <: NotificationKind]] = Map(
    "console" -> Decoder[ConsoleNotification],
    "flowdock" -> Decoder[FlowdockNotification],
    "email" -> Decoder[EmailNotification])
  def decoderFor(kind: String): Option[Decoder[NotificationKind]] =
    decoders.get(kind).map(_.asInstanceOf[Decoder[NotificationKind]])

  implicit val notificationKindEncoder: Encoder[NotificationKind] = Encoder.instance {
    case n: ConsoleNotification  => ConsoleNotification.consoleNotificationEncoder(n)
    case n: FlowdockNotification => FlowdockNotification.flowdockNotificationEncoder(n)
    case n: EmailNotification    => EmailNotification.emailNotificationEncoder(n)
    case other => throw new Exception("Internal error while serializing NotificationKind. Please report. (" + other.getClass + ")")
  }
}

abstract class NotificationContext[T <: NotificationKind](implicit ct: scala.reflect.ClassTag[T]) {
  /** before() is called once after the build, before all the send()s of this kind. */
  def before() = {}
  /** after() is called after all the send()s of this kind, for cleanup. */
  def after() = {}
  /**
   * Send the notification using the template templ (do not use the one from outcome when implementing).
   *  If the notification fails, just throw an Exception.
   */
  protected def send(n: T, templ: TemplateFormatter, outcome: BuildOutcome): Unit
  /**
   * The NotificationKind record (identified by the label 'send' in the notification record)
   * is optional; if the user does not specify it, some default is necessary.
   * If there is not acceptable default, this method can throw an exception or otherwise
   * issue a message and abort.
   */
  protected def defaultOptions: T
  /**
   *  Merges two records; if anything in "over" was changed with respect to defaultOptions,
   *  then take the value of "over". Else, get the value from "under". If both are None,
   *  return defaultOptions.
   */
  protected def mergeOptions(over: T, under: T): T

  /**
   * The client code calls notify(), which redispatches to send(), implemented in subclasses.
   */
  def notify(n: Option[NotificationKind], templ: TemplateFormatter, outcome: BuildOutcome) = {
    n match {
      case None => send(defaultOptions, templ, outcome)
      case Some(no) =>
        if (ct.runtimeClass.isInstance(no)) send(no.asInstanceOf[T], templ, outcome) else
          sys.error("Internal error: " + this.getClass.getName + " received a " + no.getClass.getName + ". Please report.")
    }
  }
  /** The client code calls mergeOptionsK, which is internally re-dispatched to mergeOptions */
  def mergeOptionsK(over: Option[NotificationKind], under: Option[NotificationKind]): NotificationKind = {
    (over, under) match {
      case (None, None) => defaultOptions
      case (Some(ov), None) => ov
      case (None, Some(un)) => un
      case (Some(ov), Some(un)) =>
        if (ct.runtimeClass.isInstance(ov) && ct.runtimeClass.isInstance(un)) mergeOptions(ov.asInstanceOf[T], un.asInstanceOf[T]) else
          sys.error("Internal error: " + this.getClass.getName + " received: " + ov.getClass.getName + "-" + un.getClass.getName + ". Please report.")
    }
  }

}

/**
 * All the addresses can be in standard RFC 822 format, either "here@there.com", or
 * "Hello Myself <here@there.com>".
 */
case class EmailNotification(
  to: SeqString = Seq.empty,
  cc: SeqString = Seq.empty,
  bcc: SeqString = Seq.empty,
  /**
   * The default sender is the account under which dbuild
   * is running right now (user@hostname). Else, specify it here.
   */
  from: Option[String] = None,
  /**
   * SMTP parameters. If not specified, messages will be sent
   * to localhost, port 25, no auth, hoping for the best.
   */
  smtp: Smtp = Smtp("localhost", None, "none", false)) extends NotificationKind
object EmailNotification {
  implicit val emailNotificationEncoder: Encoder[EmailNotification] = dropNullValues(deriveEncoder[EmailNotification])
  implicit val emailNotificationDecoder: Decoder[EmailNotification] = deriveDecoder[EmailNotification]
}

/**
 * Messages sent to a Flowdock flow, via their Push API
 */
case class FlowdockNotification(
  /** The path to a text file containing the Flowdock API token */
  token: String = "",
  /**
   * "detail" can take the value "summary", "short" (default), or
   *  "long"; it specifies the amount of detail that will be used
   *  in the Flowdock notification.
   */
  detail: String = "short",
  /**
   * The username that Flowdock will display as the sender
   *  (it need not exist in the system)
   */
  from: String = "",
  /** tags that will be appended to the message */
  tags: SeqString = Seq.empty) extends NotificationKind
object FlowdockNotification {
  implicit val flowdockNotificationEncoder: Encoder[FlowdockNotification] = deriveEncoder[FlowdockNotification]
  implicit val flowdockNotificationDecoder: Decoder[FlowdockNotification] = deriveDecoder[FlowdockNotification]
}

/**
 * Description of the smtp server to be used for email delivery.
 */
case class Smtp(
  /**
   * Specify here the smtp gateway that should be used.
   */
  server: String,
  /**
   * If your smtp server needs username/password, specify
   *  them in a file and supply the filename here. In the
   *  properties file you will need: user, host, password.
   *  The "host" property is the hostname you are connecting to,
   *  and must match the smtp server name. The "user" property
   *  is the name used during the authentication; it can be
   *  "name", or "name@somehost", depending on the providers.
   */
  credentials: Option[String] = None,
  /**
   * Set this to the desired authentication mechanism. It can be
   * starttls, ssl, submission (port 587/STARTTLS), or none. Default is ssl.
   */
  encryption: String = "ssl",
  /**
   * If using SSL/TLS, a self-signed certificate could be in use.
   * In that case, explicitly disable certificate checking here.
   */
  checkCertificate: Boolean = true)
object Smtp {
  implicit val smtpEncoder: Encoder[Smtp] =
    dropNullValues(renamedEnc("checkCertificate" -> "check-certificate")(deriveEncoder[Smtp]))
  implicit val smtpDecoder: Decoder[Smtp] =
    renamedDec("checkCertificate" -> "check-certificate")(deriveDecoder[Smtp])
}

case class ConsoleNotification() extends NotificationKind
object ConsoleNotification {
  implicit val consoleNotificationEncoder: Encoder[ConsoleNotification] = deriveEncoder[ConsoleNotification]
  implicit val consoleNotificationDecoder: Decoder[ConsoleNotification] = deriveDecoder[ConsoleNotification]
}

object NotificationKindH {
  val kinds: Map[String, java.lang.Class[? <: NotificationKind]] = Map(
    "console" -> classOf[ConsoleNotification],
    "flowdock" -> classOf[FlowdockNotification],
    "email" -> classOf[EmailNotification])
}


case class Timeouts (
  // timeout that we allow for each extraction to complete
  // (may include git/svn checkout, and Ivy resolution)
  extractionTimeout: FiniteDuration = 1.hour,

  // timeout that we allow for each build to complete (only during the build phase);
  buildTimeout: FiniteDuration = 5.hours,

  // timeout that we allow for the entire extraction phase to complete
  extractionPhaseTimeout: FiniteDuration = 6.hours,

  // timeout that we allow for the entire build phase to complete
  buildPhaseTimeout: FiniteDuration = 16.hours,

  // overall timeout for the entire dbuild to complete
  dbuildTimeout: FiniteDuration = 23.hours
)
object Timeouts {
  import Utils.{ finiteDurationEncoder, finiteDurationDecoder }
  private val renames: Seq[(String, String)] = Seq(
    "extractionTimeout" -> "extraction",
    "buildTimeout" -> "build",
    "extractionPhaseTimeout" -> "extraction-phase",
    "buildPhaseTimeout" -> "build-phase",
    "dbuildTimeout" -> "dbuild")
  implicit val timeoutsEncoder: Encoder[Timeouts] = renamedEnc(renames: _*)(deriveEncoder[Timeouts])
  implicit val timeoutsDecoder: Decoder[Timeouts] = renamedDec(renames: _*)(deriveDecoder[Timeouts])
}
