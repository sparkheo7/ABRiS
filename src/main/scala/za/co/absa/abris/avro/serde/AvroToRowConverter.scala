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

package za.co.absa.abris.avro.serde

import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.spark.sql.Row
import za.co.absa.abris.avro.format.ScalaAvroRecord
import za.co.absa.abris.avro.parsing.AvroToSparkParser
import za.co.absa.abris.avro.read.ScalaDatumReader

import scala.reflect.ClassTag

/**
  * This class contains methods for converting Avro bytes and [[GenericRecord]] instances into Spark rows.
  * @param reader
  */
private[avro] class AvroToRowConverter(reader: Option[ScalaDatumReader[ScalaAvroRecord]]) {

  private val avroParser = new AvroToSparkParser()

  /**
    * Receives binary Avro records and converts them into Spark Rows.
    */
  def convert[T](avroRecord: Array[Byte])(implicit tag: ClassTag[T]): Row = {
    val decoder = DecoderFactory.get().binaryDecoder(avroRecord, null)
    val decodedAvroData: GenericRecord = reader.get.read(null, decoder)

    avroParser.parse(decodedAvroData)
  }

  /**
    * Parses an Avro GenericRecord into a Spark row.
    */
  def convert[T](avroRecord: GenericRecord)(implicit tag: ClassTag[T]): Row = {
    avroParser.parse(avroRecord)
  }

  def convertToGenericRecord[T](avroRecord: Array[Byte])(implicit tag: ClassTag[T]): GenericRecord = {
    val decoder = DecoderFactory.get().binaryDecoder(avroRecord, null)
    reader.get.read(null, decoder)
  }
}
