/*
 * Copyright (c) 2018-2020 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.storage.bigquery
package loader

import cats.Id

import com.google.api.services.bigquery.model.TableReference

import org.apache.beam.sdk.transforms.windowing._
import org.apache.beam.sdk.io.gcp.bigquery.{BigQueryIO, InsertRetryPolicy}
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.{CreateDisposition, WriteDisposition}
import org.apache.beam.sdk.values.WindowingStrategy.AccumulationMode

import org.joda.time.{Duration, Instant}

import org.slf4j.LoggerFactory

import com.spotify.scio.ScioContext
import com.spotify.scio.values.{SCollection, SideOutput, WindowOptions}
import com.spotify.scio.coders.Coder

import io.circe.Json

import com.snowplowanalytics.snowplow.analytics.scalasdk.Data.ShreddedType

import com.snowplowanalytics.snowplow.badrows.{BadRow, Processor}

import com.snowplowanalytics.snowplow.storage.bigquery.loader.metrics._
import com.snowplowanalytics.snowplow.storage.bigquery.common.LoaderRow
import com.snowplowanalytics.snowplow.storage.bigquery.common.Config._
import com.snowplowanalytics.snowplow.storage.bigquery.loader.IdInstances._
import com.snowplowanalytics.snowplow.storage.bigquery.common.Codecs.toPayload

object Loader {
  implicit val coderBadRow: Coder[BadRow] = Coder.kryo[BadRow]

  val processor = Processor(generated.BuildInfo.name, generated.BuildInfo.version)

  val OutputWindow: Duration =
    Duration.millis(10000)

  val OutputWindowOptions = WindowOptions(
    Repeatedly.forever(AfterProcessingTime.pastFirstElementInPane()),
    AccumulationMode.DISCARDING_FIRED_PANES,
    Duration.ZERO
  )

  /** Side output for shredded types */
  val ObservedTypesOutput: SideOutput[Set[ShreddedType]] =
    SideOutput[Set[ShreddedType]]()

  /** Emit observed types every 1 minute */
  val TypesWindow: Duration =
    Duration.millis(60000)

  /** Side output for rows failed transformation */
  val BadRowsOutput: SideOutput[BadRow] =
    SideOutput[BadRow]()

  /** Run whole pipeline */
  def run(env: Environment, sc: ScioContext): Unit = {
    val (mainOutput, sideOutputs) = getData(env.resolverJson, sc, env.config.getFullInput)
      .withSideOutputs(ObservedTypesOutput, BadRowsOutput)
      .withName("splitGoodBad")
      .flatMap {
        case (Right(row), ctx) =>
          ctx.output(ObservedTypesOutput, row.inventory)
          val diff = Instant.now().getMillis - row.collectorTstamp.getMillis
          latency.update(diff) match {
            case Right(upd) => upd
            // We can get a Left if an exception is thrown.
            case Left(e) => LoggerFactory.getLogger("non-fatal").debug("Non-fatal exception:\n", e)
          }
          Some(row)
        case (Left(row), ctx) =>
          ctx.output(BadRowsOutput, row)
          None
      }

    // Emit all types observed in 1 minute
    aggregateTypes(sideOutputs(ObservedTypesOutput)).saveAsPubsub(env.config.getFullTypesTopic)

    // Sink bad rows
    sideOutputs(BadRowsOutput).map(_.compact).saveAsPubsub(env.config.getFullBadRowsTopic)

    // Unwrap LoaderRow collection to get failed inserts
    val mainOutputInternal = mainOutput.internal
    val remaining          = getOutput(env.config.load).to(getTableReference(env)).expand(mainOutputInternal).getFailedInserts

    // Sink good rows and forward failed inserts to PubSub
    sc.wrap(remaining).withName("failedInsertsSink").saveAsPubsub(env.config.getFullFailedInsertsTopic)
    ()
  }

  /** Default BigQuery output options */
  def getOutput(loadMode: LoadMode): BigQueryIO.Write[LoaderRow] = {
    val common =
      BigQueryIO
        .write()
        .withFormatFunction(SerializeLoaderRow)
        .withCreateDisposition(CreateDisposition.CREATE_NEVER)
        .withWriteDisposition(WriteDisposition.WRITE_APPEND)

    loadMode match {
      case LoadMode.StreamingInserts(retry) =>
        val streaming = common.withMethod(BigQueryIO.Write.Method.STREAMING_INSERTS)
        if (retry) {
          streaming.withFailedInsertRetryPolicy(InsertRetryPolicy.retryTransientErrors())
        } else {
          streaming.withFailedInsertRetryPolicy(InsertRetryPolicy.neverRetry())
        }
      case LoadMode.FileLoads(frequency) =>
        common
          .withMethod(BigQueryIO.Write.Method.FILE_LOADS)
          .withNumFileShards(100)
          .withTriggeringFrequency(Duration.standardSeconds(frequency.toLong))
    }
  }

  /** Group types into smaller chunks */
  def aggregateTypes(types: SCollection[Set[ShreddedType]]): SCollection[String] =
    types
      .withFixedWindows(TypesWindow, options = OutputWindowOptions)
      .withName("aggregateTypes")
      .aggregate(Set.empty[ShreddedType])(_ ++ _, _ ++ _)
      .withName("withIntervalWindow")
      .withWindow[IntervalWindow]
      .swap
      .groupByKey
      .map { case (_, groupedSets) => groupedSets.toSet.flatten }
      .withName("filterNonEmpty")
      .filter(_.nonEmpty)
      .map { types =>
        toPayload(types).noSpaces
      }

  /** Read data from PubSub topic and transform to ready to load rows */
  def getData(resolver: Json, sc: ScioContext, input: String): SCollection[Either[BadRow, LoaderRow]] =
    sc.pubsubSubscription[String](input)
      .map(parse(resolver))
      .withFixedWindows(OutputWindow, options = OutputWindowOptions)

  def getTableReference(env: Environment): TableReference =
    new TableReference()
      .setProjectId(env.config.projectId)
      .setDatasetId(env.config.datasetId)
      .setTableId(env.config.tableId)

  /**
    * Parse enriched TSV into a loader row, ready to be loaded into bigquery
    * If Loader is able to figure out that row cannot be loaded into BQ -
    * it will return return BadRow with detailed error that later can be analyzed
    * If premature check has passed, but row cannot be loaded it will be forwarded
    * to "failed inserts" topic, without additional information
    * @param resolverJson serializable Resolver's config to get it from singleton store
    * @param record enriched TSV line
    * @return either bad with error messages or entity ready to be loaded
    */
  def parse(resolverJson: Json)(record: String): Either[BadRow, LoaderRow] = {
    val resolver = singleton.ResolverSingleton.get(resolverJson)
    LoaderRow.parse[Id](resolver, processor)(record)
  }
}
