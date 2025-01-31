/*
 * Copyright 2018 Amazon.com, Inc. or its affiliates.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *
 *  3. Neither the name of the copyright holder nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *  "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 *  BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 *  FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 *  THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 *  INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 *  SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 *  HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 *  STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
 *  IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *  POSSIBILITY OF SUCH DAMAGE.
 */

package cromwell.backend.impl.aws

import cats.data.Validated._
import cats.syntax.apply._
import cats.syntax.either._
import com.typesafe.config.{Config, ConfigValue}
import common.exception.MessageAggregation
import common.validation.ErrorOr._
import common.validation.Validation._
import cromwell.backend.CommonBackendConfigurationAttributes
import cromwell.backend.impl.aws.callcaching.{AwsBatchCacheHitDuplicationStrategy, CopyCachedOutputs, UseOriginalCachedOutputs}
import cromwell.cloudsupport.aws.AwsConfiguration
import cromwell.cloudsupport.aws.auth.AwsAuthMode
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.{StringReader, ValueReader}
import org.slf4j.{Logger, LoggerFactory}

import java.net.{URI, URL}
import scala.jdk.CollectionConverters._

case class AwsBatchAttributes(fileSystem: String,
                              auth: AwsAuthMode,
                              executionBucket: String,
                              duplicationStrategy: AwsBatchCacheHitDuplicationStrategy,
                              submitAttempts: Int Refined Positive,
                              createDefinitionAttempts: Int Refined Positive,
                              fsxMntPoint: Option[List[String]],
                              efsMntPoint: Option[String],
                              efsMakeMD5: Option[Boolean],
                              efsDelocalize: Option[Boolean],
                              globLinkCommand: Option[String],
                              checkSiblingMd5: Option[Boolean]
                              )

object AwsBatchAttributes {
  lazy val Logger = LoggerFactory.getLogger(this.getClass)

  private val availableConfigKeys = CommonBackendConfigurationAttributes.commonValidConfigurationAttributeKeys ++ Set(
    "concurrent-job-limit",
    "root",
    "filesystems",
    "filesystems.local.auth",
    "filesystems.local.fsx",
    "filesystems.local.efs",
    "filesystems.local.localization",
    "filesystems.local.caching.hashing-strategy",
    "filesystems.local.caching.duplication-strategy",
    "filesystems.local.caching.check-sibling-md5",
    "filesystems.s3.auth",
    "filesystems.s3.caching.duplication-strategy",
    "auth",
    "numCreateDefinitionAttempts",
    "numSubmitAttempts",
    "default-runtime-attributes.scriptBucketName",
    "awsBatchRetryAttempts",
    "ulimits",
    "efsDelocalize",
    "efsMakeMD5",
    "glob-link-command"
  )

  private val deprecatedAwsBatchKeys: Map[String, String] = Map(
  )

  private val context = "AwsBatch"

  implicit val urlReader: ValueReader[URL] = StringReader.stringValueReader.map { URI.create(_).toURL }

  def fromConfigs(awsConfig: AwsConfiguration, backendConfig: Config): AwsBatchAttributes = {
    val configKeys = backendConfig.entrySet().asScala.toSet map { entry: java.util.Map.Entry[String, ConfigValue] => entry.getKey }
    warnNotRecognized(configKeys, availableConfigKeys, context, Logger)

    def warnDeprecated(keys: Set[String], deprecated: Map[String, String], context: String, logger: Logger) = {
      val deprecatedKeys = keys.intersect(deprecated.keySet)
      deprecatedKeys foreach { key => logger.warn(s"Found deprecated configuration key $key, replaced with ${deprecated.get(key)}") }
    }
    def parseFSx(config: List[String]): Option[List[String]] = {
      config.isEmpty match {
        case true => None
        case false => Some(config)
      }
    }

    def parseConfigString(config: String): Option[String] = {
      config.isEmpty match {
        case true => None
        case false => Some(config)
      }
    }

    warnDeprecated(configKeys, deprecatedAwsBatchKeys, context, Logger)

    val executionBucket: ErrorOr[String] = validate { backendConfig.as[String]("root") }

    val fileSysStr:ErrorOr[String] =  validate {backendConfig.hasPath("filesystems.s3") match {
      case true => "s3"
      case false => "local"
    }}

    val fileSysPath = backendConfig.hasPath("filesystems.s3") match {
      case true => "filesystems.s3"
      case false => "filesystems.local"
    }
    val filesystemAuthMode: ErrorOr[AwsAuthMode] = {
      (for {
        authName <- validate {
          backendConfig.as[String](s"${fileSysPath}.auth")
        }.toEither
        validAuth <- awsConfig.auth(authName).toEither
      } yield validAuth).toValidated
    }


    val duplicationStrategy: ErrorOr[AwsBatchCacheHitDuplicationStrategy] =
      validate {
        backendConfig.
          as[Option[String]](s"${fileSysPath}.caching.duplication-strategy").
          getOrElse("copy") match {
            case "copy" => CopyCachedOutputs
            case "reference" => UseOriginalCachedOutputs
            case other => throw new IllegalArgumentException(s"Unrecognized caching duplication strategy: $other. Supported strategies are copy and reference. See reference.conf for more details.")
          }
      }
    
    val fsxMntPoint: ErrorOr[Option[List[String]]] = validate {backendConfig.hasPath("filesystems.local.fsx") match {
        case true => parseFSx(backendConfig.getStringList("filesystems.local.fsx").asScala.toList)
        case false => None
      }
    }

    // EFS settings:
    val efsMntPoint:ErrorOr[Option[String]] = validate {backendConfig.hasPath("filesystems.local.efs") match {
        case true => parseConfigString(backendConfig.getString("filesystems.local.efs"))
        case false => None
      }
    }
    val efsMakeMD5:ErrorOr[Option[Boolean]] = validate {backendConfig.hasPath("default-runtime-attributes.efsMakeMD5") match {
        case true => Some(backendConfig.getBoolean("default-runtime-attributes.efsMakeMD5"))
        case false => None
      }
    }
    // if set for job : use that; else from defaults; else None
    val efsDelocalize:ErrorOr[Option[Boolean]] = validate {
        backendConfig.hasPath("default-runtime-attributes.efsDelocalize") match {
            case true => Some(backendConfig.getBoolean("default-runtime-attributes.efsDelocalize"))
            case false => None
      }
    }
    // from config if set.
    val globLinkCommand:ErrorOr[Option[String]] = validate {
        backendConfig.hasPath("glob-link-command") match {
            case true => Some(backendConfig.getString("glob-link-command"))
            case false => None
      }
    }
    // from config if set:
    val checkSiblingMd5:ErrorOr[Option[Boolean]] = validate {
        backendConfig.hasPath("filesystems.local.caching.check-sibling-md5") match {
            case true => Some(backendConfig.getBoolean("filesystems.local.caching.check-sibling-md5"))
            case false => None
      }
    }

    (
      fileSysStr,
      filesystemAuthMode,
      executionBucket,
      duplicationStrategy,
      backendConfig.as[ErrorOr[Int Refined Positive]]("numSubmitAttempts"),
      backendConfig.as[ErrorOr[Int Refined Positive]]("numCreateDefinitionAttempts"),
      fsxMntPoint,
      efsMntPoint,
      efsMakeMD5,
      efsDelocalize,
      globLinkCommand,
      checkSiblingMd5
    ).tupled.map((AwsBatchAttributes.apply _).tupled) match {
      case Valid(r) => r
      case Invalid(f) =>
        throw new IllegalArgumentException with MessageAggregation {
          override val exceptionContext = "AwsBatch Configuration is not valid: Errors"
          override val errorMessages = f.toList
        }
    }
  }

  implicit val ficusPositiveInt: ValueReader[ErrorOr[Int Refined Positive]] =
    new ValueReader[ErrorOr[Int Refined Positive]] {
      override def read(config: Config, path: String): ErrorOr[Refined[Int, Positive]] = {
        val int = config.getInt(path)
        refineV[Positive](int).toValidatedNel
    }
  }
}


