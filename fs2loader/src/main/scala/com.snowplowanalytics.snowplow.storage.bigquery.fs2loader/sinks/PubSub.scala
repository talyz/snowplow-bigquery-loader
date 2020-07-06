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
package com.snowplowanalytics.snowplow.storage.bigquery.fs2loader.sinks

import cats.effect.IO
import cats.syntax.all._

import scala.concurrent.duration._
import com.permutive.pubsub.producer.Model
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.grpc.{GooglePubsubProducer, PubsubProducerConfig}
import com.snowplowanalytics.snowplow.analytics.scalasdk.Data.ShreddedType
import com.snowplowanalytics.snowplow.badrows.BadRow
import com.snowplowanalytics.snowplow.storage.bigquery.common.Codecs.toPayload

object PubSub {
  sealed trait PubSubOutput extends Product with Serializable
  object PubSubOutput {
    final case class WriteBadRow(badRow: BadRow) extends PubSubOutput
    final case class WriteTableRow(tableRow: String) extends PubSubOutput
    final case class WriteObservedTypes(types: Set[ShreddedType]) extends PubSubOutput
  }

  implicit val messageEncoder: MessageEncoder[PubSubOutput] = {
    case PubSubOutput.WriteBadRow(br)       => Right(br.compact.getBytes())
    case PubSubOutput.WriteTableRow(tr)     => Right(tr.getBytes())
    case PubSubOutput.WriteObservedTypes(t) => Right(toPayload(t).noSpaces.getBytes())
  }

  def write(record: PubSubOutput, projectId: String, topic: String): IO[Unit] =
    GooglePubsubProducer
      .of[IO, PubSubOutput](
        Model.ProjectId(projectId),
        Model.Topic(topic),
        config = PubsubProducerConfig[IO](
          // TODO: Get rid of magic numbers
          batchSize         = 100,
          delayThreshold    = 100.millis,
          onFailedTerminate = e => IO.delay(println(s"Got error $e")) >> IO.unit
        )
      )
      .use { producer =>
        producer.produce(record)
      }
      .void
}