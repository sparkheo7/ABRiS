/*
 * Copyright 2018 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.abris.avro.read.confluent

import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaMetadata, SchemaRegistryClient}
import io.confluent.kafka.serializers.{AbstractKafkaAvroSerDeConfig, KafkaAvroDeserializerConfig}
import org.apache.avro.Schema
import org.apache.kafka.common.config.ConfigException
import org.slf4j.LoggerFactory
import za.co.absa.abris.avro.subject.SubjectNameStrategyAdapterFactory

import scala.collection.JavaConverters._

/**
  * This object provides methods to integrate with remote schemas through Schema Registry.
  *
  * This can be considered an "enriched" facade to the Schema Registry client.
  *
  * This is NOT THREAD SAFE, which means that multiple threads operating on this object (e.g. calling 'configureSchemaRegistry'
  * with different parameters) would operated on the same Schema Registry client, thus, leading to inconsistent behavior.
  */
object SchemaManager {

  private val logger = LoggerFactory.getLogger(SchemaManager.getClass)

  val PARAM_SCHEMA_REGISTRY_TOPIC = "schema.registry.topic"
  val PARAM_SCHEMA_REGISTRY_URL   = "schema.registry.url"
  val PARAM_VALUE_SCHEMA_ID       = "value.schema.id"
  val PARAM_KEY_SCHEMA_ID         = "key.schema.id"
  val PARAM_SCHEMA_ID_LATEST_NAME = "latest"

  val PARAM_KEY_SCHEMA_NAMING_STRATEGY   = "key.schema.naming.strategy"
  val PARAM_VALUE_SCHEMA_NAMING_STRATEGY = "value.schema.naming.strategy"

  val PARAM_SCHEMA_NAME_FOR_RECORD_STRATEGY      = "schema.name"
  val PARAM_SCHEMA_NAMESPACE_FOR_RECORD_STRATEGY = "schema.namespace"

  object SchemaStorageNamingStrategies extends Enumeration {
    val TOPIC_NAME        = "topic.name"
    val RECORD_NAME       = "record.name"
    val TOPIC_RECORD_NAME = "topic.record.name"
  }

  private var schemaRegistryClient: SchemaRegistryClient = _

  /**
    * Confluent's Schema Registry supports schemas for Kafka keys and values. What makes them different is simply the
    * what is appended to the schema name, either '-key' or '-value'.
    *
    * This method returns the subject name based on the topic and to which part of the message it corresponds.
    */
  def getSubjectName(topic: String, isKey: Boolean, schema: Schema, params: Map[String, String]): Option[String] = {
    val adapter = getSubjectNamingStrategyAdapter(isKey, params)

    if (adapter.validate(schema)) {
      val subjectName = adapter.subjectName(topic, isKey, schema)
      logger.info(s"Subject name resolved to: $subjectName")
      Some(subjectName)
    }
    else {
      logger.error(s"Invalid configuration for naming strategy. Are you using RecordName or TopicRecordName? " +
        s"If yes, are you providing SchemaManager.PARAM_SCHEMA_NAME_FOR_RECORD_STRATEGY and " +
        s"SchemaManager.PARAM_SCHEMA_NAMESPACE_FOR_RECORD_STRATEGY in the configuration map?")
      None
    }
  }

  def getSubjectName(topic: String, isKey: Boolean, schemaNameAndSpace: (String,String), params: Map[String, String]): Option[String] = {
    getSubjectName(topic, isKey, Schema.createRecord(schemaNameAndSpace._1, "", schemaNameAndSpace._2, false), params)
  }

  private def getSubjectNamingStrategyAdapter(isKey: Boolean, params: Map[String,String]) = {
    val strategy = if (isKey) {
      params.getOrElse(PARAM_KEY_SCHEMA_NAMING_STRATEGY, throw new IllegalArgumentException(s"Parameter not specified: '$PARAM_KEY_SCHEMA_NAMING_STRATEGY'"))
    }
    else {
      params.getOrElse(PARAM_VALUE_SCHEMA_NAMING_STRATEGY, throw new IllegalArgumentException(s"Parameter not specified: '$PARAM_VALUE_SCHEMA_NAMING_STRATEGY'"))
    }
    SubjectNameStrategyAdapterFactory.build(strategy)
  }

  /**
    * Configures the Schema Registry client.
    * When invoked, it expects at least [[SchemaManager.PARAM_SCHEMA_REGISTRY_URL]] to be set.
    */
  def configureSchemaRegistry(configs: Map[String,String]): Unit = {
    if (configs.nonEmpty) {
      configureSchemaRegistry(new KafkaAvroDeserializerConfig(configs.asJava))
    }
  }

  /**
    * Retrieves an Avro Schema instance from a given subject and stored with a given id.
    * It will return None if the Schema Registry client is not configured.
    */
  def getBySubjectAndId(subject: String, id: Int): Option[Schema] = {
    logger.debug(s"Trying to get schema for subject '$subject' and id '$id'")
    if (isSchemaRegistryConfigured) {
      try {
        Some(schemaRegistryClient.getBySubjectAndId(subject, id))
      }
      catch {
        case e: Exception =>
          e.printStackTrace()
          None
      }
    }
    else None
  }

  /**
    * Retrieves an Avro [[SchemaMetadata]] instance from a given subject and stored with a given version.
    * It will return None if the Schema Registry client is not configured.
    */
  def getBySubjectAndVersion(subject: String, version: Int): Option[SchemaMetadata] = {
    logger.debug(s"Trying to get schema for subject '$subject' and version '$version'")
    if (isSchemaRegistryConfigured) {
      try {
        Some(schemaRegistryClient.getSchemaMetadata(subject, version))
      }
      catch {
        case e: Exception =>
          e.printStackTrace()
          None
      }
    }
    else None
  }

  def getById(id: Int): Option[Schema] = getBySubjectAndId(null, id)

  /**
    * Retrieves the id corresponding to the latest schema available in Schema Registry.
    */
  def getLatestVersionId(subject: String): Option[Int] = {
    logger.info(s"Trying to get latest schema version id for subject '$subject'")
    if (isSchemaRegistryConfigured) {
      try {
        Some(schemaRegistryClient.getLatestSchemaMetadata(subject).getId)
      }
      catch {
        case e: Exception =>
          e.printStackTrace()
          None
      }
    }
    else None
  }

  /**
    * Registers a schema into a given subject, returning the id the registration received.
    *
    * Afterwards the schema can be identified by this id.
    */
  def register(schema: Schema, subject: String): Option[Int] = {
    if (isSchemaRegistryConfigured) Some(schemaRegistryClient.register(subject, schema)) else None
  }

  /**
    * Checks if SchemaRegistry has been configured, i.e. if it is null
    */
  def isSchemaRegistryConfigured: Boolean = schemaRegistryClient != null

  /**
    * Configures the Schema Registry client.
    * When invoked, it expects at least the [[SchemaManager.PARAM_SCHEMA_REGISTRY_URL]] to be set.
    */
  private def configureSchemaRegistry(config: AbstractKafkaAvroSerDeConfig): Unit = {
    try {
      val urls = config.getSchemaRegistryUrls
      val maxSchemaObject = config.getMaxSchemasPerSubject

      if (null == schemaRegistryClient) {
        schemaRegistryClient = new CachedSchemaRegistryClient(urls, maxSchemaObject)
      }
    } catch {
      case e: io.confluent.common.config.ConfigException => throw new ConfigException(e.getMessage)
    }
  }

  /**
    * This class uses [[CachedSchemaRegistryClient]] by default. This method can override the default.
    *
    * The incoming instance MUST be already configured.
    *
    * Useful for tests using mocked SchemaRegistryClient instances.
    */
  def setConfiguredSchemaRegistry(schemaRegistryClient: SchemaRegistryClient): Unit = {
    this.schemaRegistryClient = schemaRegistryClient
  }

  /**
    * Checks if a new schema is compatible with the latest schema registered for a given subject.
    */
  def isCompatible(newSchema: Schema, subject: String): Boolean = {
    this.schemaRegistryClient.testCompatibility(subject, newSchema)
  }

  /**
    * Checks if a given schema exists in Schema Registry.
    */
  def exists(subject: String): Boolean = {
    try {
      schemaRegistryClient.getLatestSchemaMetadata(subject)
      true
    }
    catch {
      case e: Exception => {
        if (e.getMessage.contains("Subject not found") || e.getMessage.contains("No schema registered")) {
          logger.info(s"Subject not registered: '$subject'")
        }
        else {
          logger.error(s"Problems found while retrieving metadata for subject '$subject'", e)
        }
      }
        false
    }
  }

  /**
    * Resets this manager to its initial state, before being configured.
    */
  def reset(): Unit = schemaRegistryClient = null
}
