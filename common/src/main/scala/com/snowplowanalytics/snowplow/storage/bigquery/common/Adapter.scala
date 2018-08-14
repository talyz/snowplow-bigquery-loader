/*
 * Copyright (c) 2018 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.storage.bigquery.common

import scala.collection.JavaConverters._

import com.google.api.services.bigquery.model.TableRow
import com.google.cloud.bigquery.{Field, FieldList, LegacySQLTypeName}

import com.snowplowanalytics.iglu.schemaddl.bigquery.Generator._
import com.snowplowanalytics.iglu.schemaddl.bigquery.{Field => DdlField, _}


/** Transform dependency-free schema-ddl AST into Google Cloud Java definitions */
object Adapter {

  def fromColumn(column: Column): Field =
    adaptField(column.bigQueryField)
      .toBuilder
      .setDescription(column.version.toSchemaUri)
      .build()

  def adaptRow(row: Row): AnyRef = row match {
    case Row.Null => null
    case Row.Primitive(value) => value.asInstanceOf[AnyRef]
    case Row.Repeated(rows) =>
      rows.map(adaptRow).asJava
    case Row.Record(fields) =>
      val tableRow = new TableRow()
      fields.foreach { case (key, value) => tableRow.set(key, adaptRow(value)) }
      tableRow
  }

  def adaptField(bigQueryField: DdlField): Field =
    bigQueryField match {
      case DdlField(name, record @ Type.Record(fields), mode) =>
        val subFields = fields.map(adaptField)
        val fieldsList = FieldList.of(subFields.asJava)
        Field.newBuilder(name, adaptType(record), fieldsList)
          .setMode(adaptMode(mode))
          .build()
      case DdlField(name, fieldType, mode @ Mode.Repeated) =>
        adaptField(DdlField(name, fieldType, mode))
      case DdlField(name, fieldType, mode) =>
        Field.newBuilder(name, adaptType(fieldType))
          .setMode(adaptMode(mode))
          .build()
    }

  def adaptMode(fieldMode: Mode): Field.Mode =
    fieldMode match {
      case Mode.Nullable => Field.Mode.NULLABLE
      case Mode.Required => Field.Mode.REQUIRED
      case Mode.Repeated => Field.Mode.REPEATED
    }

  def adaptType(fieldType: Type): LegacySQLTypeName =
    fieldType match {
      case Type.Timestamp => LegacySQLTypeName.TIMESTAMP
      case Type.Integer => LegacySQLTypeName.INTEGER
      case Type.Boolean => LegacySQLTypeName.BOOLEAN
      case Type.String => LegacySQLTypeName.STRING
      case Type.Float => LegacySQLTypeName.FLOAT
      case Type.Bytes => LegacySQLTypeName.BYTES
      case Type.Date => LegacySQLTypeName.DATE
      case Type.DateTime => LegacySQLTypeName.DATETIME
      case Type.Record(_) => LegacySQLTypeName.RECORD
    }
}