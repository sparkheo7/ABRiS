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

package za.co.absa.abris.avro.format

import java.lang.{Boolean, Double, Float, Long}

import org.apache.avro.{Schema, SchemaBuilder}
import org.apache.avro.Schema.Type
import org.apache.avro.generic.{GenericRecord, IndexedRecord}
import org.apache.avro.io.DecoderFactory
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types._
import org.scalatest.FlatSpec
import za.co.absa.abris.avro.parsing.utils.AvroSchemaUtils
import za.co.absa.abris.avro.read.ScalaDatumReader
import za.co.absa.abris.examples.data.generation.{AvroDataUtils, TestSchemas}

import scala.collection.JavaConverters._
import scala.collection._

class SparkAvroConversionsSpec extends FlatSpec {
  
    private val structType = StructType(
      Seq(
        StructField("int1", IntegerType, false),
        StructField("long1", LongType, false),
        StructField("map1", new MapType(StringType, IntegerType, false), false),
        StructField("array1", new ArrayType(LongType, false), false),
        StructField("struct2", StructType(
          Seq(
            StructField("string2", StringType, true),
            StructField("string3", StringType, false)
          )
        ), false),
        StructField("double1", DoubleType, false),
        StructField("struct3", StructType(
          Seq(
            StructField("int2", IntegerType, false),
            StructField("float1", FloatType, false)
          )
        ), false),
        StructField("bytes", BinaryType, false)
      )
    )   
  
  behavior of "SparkAvroConversions"
     
  it should "convert records into byte[]" in {
    val testData = immutable.Map[String, Object](
      "string" ->  "A Test String",
      "float" ->   new Float(Float.MAX_VALUE),
      "int" ->     new Integer(Integer.MAX_VALUE),
      "long" ->    new Long(Long.MAX_VALUE),
      "double" ->  new Double(Double.MAX_VALUE),
      "boolean" -> new Boolean(true))

    val record = AvroDataUtils.mapToGenericRecord(testData, TestSchemas.NATIVE_SCHEMA_SPEC)
    val schema = AvroSchemaUtils.parse(TestSchemas.NATIVE_SCHEMA_SPEC)    
    val bytes = SparkAvroConversions.toByteArray(record, schema)
    val parsedRecord = parse(bytes, schema)
    
    assert(parsedRecord.toString() == record.toString())
  }     
  
  it should "convert Rows to Avro binary records" in {
    val map = Map("key" -> 3)
    val array: mutable.ListBuffer[Any] = new mutable.ListBuffer()
    array.append(4l)
    array.append(5l)
    
    val data: Array[Any] = Array(1, 2l, map, array, Row("st1", "st2"), 6d, Row(7, 8f), Array[Byte](42, 8, 9, 6))
    val row = new GenericRowWithSchema(data, structType)
    val avroSchema = SparkAvroConversions.toAvroSchema(structType, "name", "namespace")    
    val sparkSchema = SparkAvroConversions.toSqlType(avroSchema)
    val rowBytes = SparkAvroConversions.rowToBinaryAvro(row, sparkSchema, avroSchema)
    val record: GenericRecord = parse(rowBytes, avroSchema).asInstanceOf[GenericRecord]
    for (i <- 0 until (data.length -1)) assert(record.get(i) == data(i))
    assert(record.get(7).asInstanceOf[Array[Byte]].sameElements(data(7).asInstanceOf[Array[Byte]]))
  }
  
  it should "convert Avro schemas to SQL types" in {
    val schema = AvroSchemaUtils.parse(TestSchemas.COMPLEX_SCHEMA_SPEC)
    val sql = SparkAvroConversions.toSqlType(schema)    
    val schemaFromSql = SparkAvroConversions.toAvroSchema(sql, schema.getName, schema.getNamespace)
    
    schema.getFields.asScala.foreach(field => assert(schema.getField(field.name).toString == schemaFromSql.getField(field.name).toString))
  }
  
  it should "convert SQL types to Avro schemas" in {    
    val schemaName = "teste_name"
    val schemaNamespace = "teste_namespace"
    
    val schema = SparkAvroConversions.toAvroSchema(structType, schemaName, schemaNamespace)
    
    assert(schema.getName == schemaName)
    assert(schema.getNamespace == schemaNamespace)
    assert(schema.getField("int1").schema().getType == Type.INT)
    assert(schema.getField("long1").schema().getType == Type.LONG)
    assert(schema.getField("map1").schema().getType == Type.MAP)
    assert(schema.getField("array1").schema().getType == Type.ARRAY)
    assert(schema.getField("struct2").schema().getType == Type.RECORD)
    assert(schema.getField("double1").schema().getType == Type.DOUBLE)
    assert(schema.getField("struct3").schema().getType == Type.RECORD)
    assert(schema.getField("bytes").schema().getType == Type.BYTES)

    val map1 = schema.getField("map1").schema()
    assert(map1.getValueType.getType == Type.INT)
    
    val array1 = schema.getField("array1").schema()
    assert(array1.getElementType.getType == Type.LONG)
    
    val struct2 = schema.getField("struct2").schema()
    assert(struct2.getField("string2").schema().getType == Type.UNION) // nullable fields are "unioned" with null
    assert(struct2.getField("string3").schema().getType == Type.STRING)
    
    val struct3 = schema.getField("struct3").schema()
    assert(struct3.getField("int2").schema().getType == Type.INT)
    assert(struct3.getField("float1").schema().getType == Type.FLOAT)
  }

  it should "convert fixed and bytes type" in {

    val avroSchema = SchemaBuilder
      .record("test_record")
      .namespace("test_namespace")
      .fields()
        .name("fixed_name").`type`().fixed("fixed_name").size(3).noDefault()
        .name("bytes_name").`type`().bytesType().noDefault()
      .endRecord()

    val sparkType = StructType(
      Seq(
        StructField("fixed_name", BinaryType, false),
        StructField("bytes_name", BinaryType, false)
      )
    )

    val testBytes = Array[Byte](42,0,1)
    val row = new GenericRowWithSchema(Array[Any](testBytes, testBytes), structType)
    val rowBytes = SparkAvroConversions.rowToBinaryAvro(row, sparkType, avroSchema)

    val numberOfBytes = 6 // 3 is encoded as 6 because of avro zigzag encoding
    val expectedResult = testBytes ++ (numberOfBytes +: testBytes)
    assert(rowBytes.sameElements(expectedResult))

    val record: GenericRecord = parse(rowBytes, avroSchema).asInstanceOf[GenericRecord]
    assert(record.get(0).asInstanceOf[Array[Byte]].sameElements(testBytes))
    assert(record.get(1).asInstanceOf[Array[Byte]].sameElements(testBytes))
  }

  private def parse(bytes: Array[Byte], schema: Schema): IndexedRecord = {
    val decoder = DecoderFactory.get().binaryDecoder(bytes, null)    
    val reader = new ScalaDatumReader[IndexedRecord](schema)
    reader.read(null, decoder)    
  }
}