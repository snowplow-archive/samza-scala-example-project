/*
 * Copyright (c) 2015 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.samza.example

// Scala
import collection.mutable.HashSet

// Samza
import org.apache.samza.config.Config
import org.apache.samza.task.{
  StreamTask,
  InitableTask,
  WindowableTask,
  MessageCollector,
  TaskContext,
  TaskCoordinator
}
import org.apache.samza.system.{
  IncomingMessageEnvelope,
  OutgoingMessageEnvelope,
  SystemStream
}

// This project
import events.{InboundEvent, WindowSummaryEvent}

/**
 * This task is very simple. All it does is take messages that it receives, and
 * sends them to a Kafka topic called wikipedia-raw.
 */
class ExampleStreamTask extends StreamTask with InitableTask with WindowableTask {

  private var store: CountStore = _
  private var updatedKeys: KeySet = _

  /**
   * One-off initialization for our task.
   */
  def init(config: Config, context: TaskContext) {
    this.store = context.getStore("example-project").asInstanceOf[CountStore]
    this.updatedKeys = new KeySet()
  }

  /**
   * Invoked for each incoming event.
   */
  override def process(envelope: IncomingMessageEnvelope,
    collector: MessageCollector, coordinator: TaskCoordinator) {

    // TODO: we could implement our own Json4s Serde for Samza instead
    val event = {
      val bytes = envelope.getMessage.asInstanceOf[Array[Byte]]
      InboundEvent.fromJsonBytes(bytes)
    }

    val bucket = BucketingStrategy.bucket(event.timestamp)    
    val key = List(bucket, event.`type`).mkString(AsciiUnitSeparator)
    val count: Integer = Option(store.get(key)).getOrElse(0)

    store.put(key, count + 1)
    updatedKeys.add(key)
  }

  /**
   * Invoked every N seconds.
   */
  override def window(collector: MessageCollector, coordinator: TaskCoordinator) {

    val event = {
      val counts = updatedKeys
        .map(k => (k -> store.get(k)))
        .toMap
      WindowSummaryEvent(counts)
    }

    collector.send(new OutgoingMessageEnvelope(
      new SystemStream("kafka", "example-project-window-summary"), event.toJsonBytes))

    updatedKeys.clear()
  }
}
