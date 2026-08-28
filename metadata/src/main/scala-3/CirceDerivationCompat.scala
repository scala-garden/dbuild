package com.typesafe.dbuild.model

import io.circe.derivation.{ Configuration, ConfiguredEncoder, ConfiguredDecoder }
import io.circe.{ Encoder, Decoder }
import scala.deriving.Mirror

/**
 * A stable-named `deriveEncoder`/`deriveDecoder` pair, backed on Scala 3 by circe-core's
 * own native io.circe.derivation.{ConfiguredEncoder,ConfiguredDecoder} (which supports
 * Scala default parameter values as fallbacks for missing JSON fields). See the
 * scala-2.12 variant of this file for the Scala 2.12 counterpart, backed by
 * circe-generic-extras.
 */
object CirceDerivationCompat {
  given Configuration = Configuration.default.withDefaults

  inline def deriveEncoder[A](using inline mirror: Mirror.Of[A]): Encoder.AsObject[A] = ConfiguredEncoder.derived[A]
  inline def deriveDecoder[A](using inline mirror: Mirror.Of[A]): Decoder[A] = ConfiguredDecoder.derived[A]
}
