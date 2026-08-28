package com.typesafe.dbuild.model

import io.circe.generic.extras.Configuration
import io.circe.{ Encoder, Decoder }

/**
 * A stable-named `deriveEncoder`/`deriveDecoder` pair, backed on Scala 2.12 by
 * circe-generic-extras (which supports Scala default parameter values as fallbacks
 * for missing JSON fields, unlike plain circe-generic). See the scala-3 variant of
 * this file for the Scala 3 counterpart, backed by circe-core's own native
 * io.circe.derivation.{ConfiguredEncoder,ConfiguredDecoder}.
 */
object CirceDerivationCompat {
  implicit val circeConfig: Configuration = Configuration.default.withDefaults

  def deriveEncoder[A](implicit ev: shapeless.Lazy[io.circe.generic.extras.encoding.ConfiguredAsObjectEncoder[A]]): Encoder.AsObject[A] =
    io.circe.generic.extras.semiauto.deriveEncoder[A]
  def deriveDecoder[A](implicit ev: shapeless.Lazy[io.circe.generic.extras.decoding.ConfiguredDecoder[A]]): Decoder[A] =
    io.circe.generic.extras.semiauto.deriveDecoder[A]
}
