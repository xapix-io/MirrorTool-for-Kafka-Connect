/**
 * Copyright 2018 Comcast Cable Communications Management, LLC
 * Licensed under the Apache License, Version 2.0 (the "License"); * you may not use this file except in compliance with the License. * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.comcast.kafka.connect.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.source.SourceTask;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.storage.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.Duration;

public class KafkaSourceTask extends SourceTask {
  private static final Logger logger = LoggerFactory.getLogger(KafkaSourceTask.class);
  public static final String TOPIC_PARTITION_KEY = "topic:partition";
  public static final String OFFSET_KEY = "offset";

  // Used to ensure we can be nice and call consumer.close() on shutdown
  private final CountDownLatch stopLatch = new CountDownLatch(1);
  // Flag to the poll() loop that we are awaiting shutdown so it can clean up.
  private AtomicBoolean stop = new AtomicBoolean(false);
  // Flag to the stop() function that it needs to wait for poll() to wrap up
  // before trying to close the kafka consumer.
  private AtomicBoolean poll = new AtomicBoolean(false);
  // Used to enforce synchronized access to stop and poll
  private final Object stopLock = new Object();

  // Settings
  private int maxShutdownWait;
  private int pollTimeout;
  private boolean includeHeaders;

  // Consumer
  private KafkaConsumer<byte[], byte[]> consumer;

  // Source converters
  private Converter sourceKeyConverter;
  private String sourceKeyConverterTopic;
  private Converter sourceValueConverter;
  private String sourceValueConverterTopic;

  public void start(Map<String, String> opts) {
    logger.info("{}: task is starting.", this);
    KafkaSourceConnectorConfig sourceConnectorConfig = new KafkaSourceConnectorConfig(opts);
    maxShutdownWait = sourceConnectorConfig.getInt(KafkaSourceConnectorConfig.MAX_SHUTDOWN_WAIT_MS_CONFIG);
    pollTimeout = sourceConnectorConfig.getInt(KafkaSourceConnectorConfig.POLL_LOOP_TIMEOUT_MS_CONFIG);
    includeHeaders = sourceConnectorConfig.getBoolean(KafkaSourceConnectorConfig.INCLUDE_MESSAGE_HEADERS_CONFIG);
    try {
        sourceKeyConverter = (Converter) Class.forName(sourceConnectorConfig.getString(KafkaSourceConnectorConfig.SOURCE_KEY_CONVERTER_CONFIG)).newInstance();
    } catch (Exception e) {
        logger.error("{}: Can not load class for Key Converter", this);
        return;
    }
    sourceKeyConverter.configure(sourceConnectorConfig.getSourceKeyConverterProperties(), false);
    sourceKeyConverterTopic = sourceConnectorConfig.getString(KafkaSourceConnectorConfig.SOURCE_KEY_CONVERTER_TOPIC_CONFIG);
    try {
        sourceValueConverter = (Converter) Class.forName(sourceConnectorConfig.getString(KafkaSourceConnectorConfig.SOURCE_VALUE_CONVERTER_CONFIG)).newInstance();
    } catch (Exception e) {
        logger.error("{}: Can not load class for Value Converter", this);
        return;
    }
    sourceValueConverter.configure(sourceConnectorConfig.getSourceValueConverterProperties(), false);
    sourceValueConverterTopic = sourceConnectorConfig.getString(KafkaSourceConnectorConfig.SOURCE_VALUE_CONVERTER_TOPIC_CONFIG);
    String unknownOffsetResetPosition = sourceConnectorConfig
        .getString(KafkaSourceConnectorConfig.CONSUMER_AUTO_OFFSET_RESET_CONFIG);

    // Get the leader topic partitions to work with
    List<LeaderTopicPartition> leaderTopicPartitions = Arrays
        .asList(opts.get(KafkaSourceConnectorConfig.TASK_LEADER_TOPIC_PARTITION_CONFIG).split(",")).stream()
        .map(LeaderTopicPartition::fromString).collect(Collectors.toList());

    // retrieve the existing offsets (if any) for the configured partitions
    List<Map<String, String>> offsetLookupPartitions = leaderTopicPartitions.stream()
        .map(leaderTopicPartition -> Collections.singletonMap(TOPIC_PARTITION_KEY,
            leaderTopicPartition.toTopicPartitionString()))
        .collect(Collectors.toList());
    Map<String, Long> topicPartitionStringsOffsets = context.offsetStorageReader().offsets(offsetLookupPartitions)
        .entrySet().stream()
        .filter(e -> e != null && e.getKey() != null && e.getKey().get(TOPIC_PARTITION_KEY) != null
            && e.getValue() != null && e.getValue().get(OFFSET_KEY) != null)
        .collect(Collectors.toMap(e -> e.getKey().get(TOPIC_PARTITION_KEY), e -> (long) e.getValue().get(OFFSET_KEY)));
    // Set up Kafka consumer
    consumer = new KafkaConsumer<byte[], byte[]>(sourceConnectorConfig.getKafkaConsumerProperties());

    // Get topic partitions and offsets so we can seek() to them
    Map<TopicPartition, Long> topicPartitionOffsets = new HashMap<>();
    List<TopicPartition> topicPartitionsWithUnknownOffset = new ArrayList<>();
    for (LeaderTopicPartition leaderTopicPartition : leaderTopicPartitions) {
      String topicPartitionString = leaderTopicPartition.toTopicPartitionString();
      TopicPartition topicPartition = leaderTopicPartition.toTopicPartition();
      if (topicPartitionStringsOffsets.containsKey(topicPartitionString)) {
        topicPartitionOffsets.put(topicPartition, topicPartitionStringsOffsets.get(topicPartitionString));
      } else {
        // No stored offset? No worries, we will place it it the list to lookup
        topicPartitionsWithUnknownOffset.add(topicPartition);
      }
    }

    // Set default offsets for partitions without stored offsets
    if (topicPartitionsWithUnknownOffset.size() > 0) {
      Map<TopicPartition, Long> defaultOffsets;
      logger.info("The following partitions do not have existing offset data: {}", topicPartitionsWithUnknownOffset);
      if (unknownOffsetResetPosition.equals(OffsetResetStrategy.EARLIEST.toString().toLowerCase())) {
        logger.info("Using earliest offsets for partitions without existing offset data.");
        defaultOffsets = consumer.beginningOffsets(topicPartitionsWithUnknownOffset);
      } else if (unknownOffsetResetPosition.equals(OffsetResetStrategy.LATEST.toString().toLowerCase())) {
        logger.info("Using latest offsets for partitions without existing offset data.");
        defaultOffsets = consumer.endOffsets(topicPartitionsWithUnknownOffset);
      } else if (unknownOffsetResetPosition.equals(OffsetResetStrategy.NONE.toString().toLowerCase())) {
        logger.info("Will try to use existing consumer group offsets for partitions.");
        defaultOffsets = topicPartitionsWithUnknownOffset.stream()
            .collect(Collectors.toMap(Function.identity(), tp -> {
                OffsetAndMetadata committed = consumer.committed(tp);
                if (committed == null) {
                    throw new ConnectException(
                        String.format("Unable to find committed offsets for consumer group for when %s=%s",
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                            OffsetResetStrategy.NONE.toString().toLowerCase()));
                }
                return committed.offset();
            }));
      } else {
        logger.warn(
            "Config value {}, is set to an unknown value: {}. Partitions without existing offset data will not be consumed.",
            KafkaSourceConnectorConfig.CONSUMER_AUTO_OFFSET_RESET_CONFIG, unknownOffsetResetPosition);
        defaultOffsets = new HashMap<>();
      }
      topicPartitionOffsets.putAll(defaultOffsets);
    }

    // List of topic partitions to assign
    List<TopicPartition> topicPartitionsToAssign = new ArrayList<>(topicPartitionOffsets.keySet());
    consumer.assign(topicPartitionsToAssign);

    // Seek to desired offset for each partition
    topicPartitionOffsets.forEach((key, value) -> consumer.seek(key, value));
  }

  @Override
  public List<SourceRecord> poll() {
    if (logger.isDebugEnabled())
      logger.debug("{}: poll()", this);
    synchronized (stopLock) {
      if (!stop.get())
        poll.set(true);
    }
    ArrayList<SourceRecord> records = new ArrayList<>();
    if (poll.get()) {
      try {
        ConsumerRecords<byte[], byte[]> krecords = consumer.poll(Duration.ofMillis(pollTimeout));
        if (logger.isDebugEnabled())
          logger.debug("{}: Got {} records from source.", this, krecords.count());
        for (ConsumerRecord<byte[], byte[]> krecord : krecords) {
          Map<String, String> sourcePartition = Collections.singletonMap(TOPIC_PARTITION_KEY,
              krecord.topic().concat(":").concat(Integer.toString(krecord.partition())));
          Map<String, Long> sourceOffset = Collections.singletonMap(OFFSET_KEY, krecord.offset());
          String sourceTopic = krecord.topic();
          String destinationTopic = sourceTopic;
          SchemaAndValue recordKey = sourceKeyConverter.toConnectData(sourceKeyConverterTopic, krecord.key());
          SchemaAndValue recordValue = sourceValueConverter.toConnectData(sourceValueConverterTopic, krecord.value());
          long recordTimestamp = krecord.timestamp();
          if (logger.isDebugEnabled()) {
            logger.trace(
                "Task: sourceTopic:{} sourcePartition:{} sourceOffSet:{} destinationTopic:{}, key:{}, valueSize:{}",
                sourceTopic, krecord.partition(), krecord.offset(), destinationTopic, recordKey,
                krecord.serializedValueSize());
          }
          if (includeHeaders) {
            // Mapping from source type: org.apache.kafka.common.header.Headers, to
            // destination type: org.apache.kafka.connect.Headers
            Headers sourceHeaders = krecord.headers();
            ConnectHeaders destinationHeaders = new ConnectHeaders();
            for (Header header : sourceHeaders) {
              if (header != null) {
                destinationHeaders.add(header.key(), header.value(), Schema.OPTIONAL_BYTES_SCHEMA);
              }
            }
            records.add(new SourceRecord(sourcePartition, sourceOffset, destinationTopic, null, recordKey.schema(),
                                         recordKey.value(), recordValue.schema(), recordValue.value(), recordTimestamp, destinationHeaders));
          } else {
            records.add(new SourceRecord(sourcePartition, sourceOffset, destinationTopic, null, recordKey.schema(),
                                         recordKey.value(), recordValue.schema(), recordValue.value(), recordTimestamp));
          }
        }
      } catch (WakeupException e) {
        logger.info("{}: Caught WakeupException. Probably shutting down.", this);
      }
    }
    poll.set(false);
    // If stop has been set processing, then stop the consumer.
    if (stop.get()) {
      logger.debug("{}: stop flag set during poll(), opening stopLatch", this);
      stopLatch.countDown();
    }
    if (logger.isDebugEnabled())
      logger.debug("{}: Returning {} records to connect", this, records.size());
    return records;
  }

  @Override
  public synchronized void stop() {
    long startWait = System.currentTimeMillis();
    synchronized (stopLock) {
      stop.set(true);
      logger.info("{}: stop() called. Waking up consumer and shutting down", this);
      consumer.wakeup();
      if (poll.get()) {
        logger.info("{}: poll() active, awaiting for consumer to wake before attempting to shut down consumer", this);
        try {
          stopLatch.await(Math.max(0, maxShutdownWait - (System.currentTimeMillis() - startWait)),
              TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          logger.warn("{}: Got InterruptedException while waiting on stopLatch", this);
        }
      }
      logger.info("{}: Shutting down consumer.", this);
      consumer.close(Duration.ofMillis(Math.max(0, maxShutdownWait - (System.currentTimeMillis() - startWait))));
    }
    logger.info("{}: task has been stopped", this);
  }

  @Override
  public String version() {
    return Version.version();
  }

  public String toString() {
    return "KafkaSourceTask@" + Integer.toHexString(hashCode());
  }

}
