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

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Validator;
import org.apache.kafka.common.config.ConfigDef.ValidString;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class KafkaSourceConnectorConfig extends AbstractConfig {

  private static final Validator NON_EMPTY_LIST_VALIDATOR = new ConfigDef.Validator() {
    @Override
    @SuppressWarnings("unchecked")
    public void ensureValid(String name, Object value) {
      if (((List<String>) value).isEmpty()) {
        throw new ConfigException("At least one bootstrap server must be configured in " + name);
      }
    }
  };

  private static final Validator TOPIC_WHITELIST_REGEX_VALIDATOR = new ConfigDef.Validator() {
    @Override
    public void ensureValid(String name, Object value) {
      getTopicWhitelistPattern((String) value);
    }
  };

  // Config Prefixes
  public static final String SOURCE_PREFIX = "source.";
  public static final String DESTINATION_PREFIX = "destination.";

  // Any CONFIG beginning with this prefix will set the CONFIG parameters for the
  // kafka consumer used in this connector
  public static final String CONSUMER_PREFIX = "connector.consumer.";
  // Any CONFIG beginning with this prefix will set the CONFIG parameters for the
  // admin client used by the partition monitor
  public static final String ADMIN_CLIENT_PREFIX = "connector.admin.";

  public static final String TASK_PREFIX = "task.";

  public static final String SOURCE_KEY_CONVERTER_PREFIX = "source.key.converter.";
  public static final String SOUCRE_VALUE_CONVERTER_PREFIX = "source.value.converter.";

  // Topic partition list we send to each task. Not user configurable.
  public static final String TASK_LEADER_TOPIC_PARTITION_CONFIG = TASK_PREFIX.concat("leader.topic.partitions");

  // General Connector CONFIG
  // Topics
  public static final String SOURCE_TOPIC_WHITELIST_CONFIG = SOURCE_PREFIX.concat("topic.whitelist");
  public static final String SOURCE_TOPIC_WHITELIST_DOC = "Regular expressions indicating the topics to consume from the source cluster. "
      + "Under the hood, the regex is compiled to a <code>java.util.regex.Pattern</code>. "
      + "For convenience, comma (',') is interpreted as interpreted as the regex-choice symbol ('|').";
  public static final Object SOURCE_TOPIC_WHITELIST_DEFAULT = ConfigDef.NO_DEFAULT_VALUE;

  // Message headers
  public static final String INCLUDE_MESSAGE_HEADERS_CONFIG = "include.message.headers";
  public static final String INCLUDE_MESSAGE_HEADERS_DOC = "Indicates whether message headers from source records should be included in output";
  public static final boolean INCLUDE_MESSAGE_HEADERS_DEFAULT = true;

  // Partition Monitor
  public static final String TOPIC_LIST_TIMEOUT_MS_CONFIG = "topic.list.timeout.ms";
  public static final String TOPIC_LIST_TIMEOUT_MS_DOC = "Amount of time the partition monitor thread should wait for kafka to return topic information before logging a timeout error.";
  public static final int TOPIC_LIST_TIMEOUT_MS_DEFAULT = 60000;
  public static final String TOPIC_LIST_POLL_INTERVAL_MS_CONFIG = "topic.list.poll.interval.ms";
  public static final String TOPIC_LIST_POLL_INTERVAL_MS_DOC = "How long to wait before re-querying the source cluster for a change in the partitions to be consumed";
  public static final int TOPIC_LIST_POLL_INTERVAL_MS_DEFAULT = 300000;
  public static final String RECONFIGURE_TASKS_ON_LEADER_CHANGE_CONFIG = "reconfigure.tasks.on.partition.leader.change";
  public static final String RECONFIGURE_TASKS_ON_LEADER_CHANGE_DOC = "Indicates whether the partition monitor should request a task reconfiguration when partition leaders have changed";
  public static final boolean RECONFIGURE_TASKS_ON_LEADER_CHANGE_DEFAULT = false;
  // Internal Connector Timing
  public static final String POLL_LOOP_TIMEOUT_MS_CONFIG = "poll.loop.timeout.ms";
  public static final String POLL_LOOP_TIMEOUT_MS_DOC = "Maximum amount of time to wait in each poll loop without data before cancelling the poll and returning control to the worker task";
  public static final int POLL_LOOP_TIMEOUT_MS_DEFAULT = 1000;
  public static final String MAX_SHUTDOWN_WAIT_MS_CONFIG = "max.shutdown.wait.ms";
  public static final String MAX_SHUTDOWN_WAIT_MS_DOC = "Maximum amount of time to wait before forcing the consumer to close";
  public static final int MAX_SHUTDOWN_WAIT_MS_DEFAULT = 2000;

  // General Source Kafka Config - Applies to Consumer and Admin Client if not
  // overridden by CONSUMER_PREFIX or ADMIN_CLIENT_PREFIX
  public static final String SOURCE_BOOTSTRAP_SERVERS_CONFIG = SOURCE_PREFIX
      .concat(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG);
  public static final String SOURCE_BOOTSTRAP_SERVERS_DOC = "list of kafka brokers to use to bootstrap the source cluster";
  public static final Object SOURCE_BOOTSTRAP_SERVERS_DEFAULT = ConfigDef.NO_DEFAULT_VALUE;

  // These are the kafka consumer configs we override defaults for
  // Note that *any* kafka consumer config can be set by adding the
  // CONSUMER_PREFIX in front of the standard consumer config strings
  public static final String CONSUMER_MAX_POLL_RECORDS_CONFIG = SOURCE_PREFIX
      .concat(ConsumerConfig.MAX_POLL_RECORDS_CONFIG);
  public static final String CONSUMER_MAX_POLL_RECORDS_DOC = "Maximum number of records to return from each poll of the consumer";
  public static final int CONSUMER_MAX_POLL_RECORDS_DEFAULT = 500;
  public static final String CONSUMER_AUTO_OFFSET_RESET_CONFIG = SOURCE_PREFIX
      .concat(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
  public static final String CONSUMER_AUTO_OFFSET_RESET_DOC = "If there is no stored offset for a partition, where to reset from [earliest|latest|none].";
  public static final String CONSUMER_AUTO_OFFSET_RESET_DEFAULT = "earliest";
  public static final ValidString CONSUMER_AUTO_OFFSET_RESET_VALIDATOR = ConfigDef.ValidString.in(
      OffsetResetStrategy.EARLIEST.toString().toLowerCase(), OffsetResetStrategy.LATEST.toString().toLowerCase(),
      OffsetResetStrategy.NONE.toString().toLowerCase());
  public static final String CONSUMER_KEY_DESERIALIZER_CONFIG = SOURCE_PREFIX
      .concat(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
  public static final String CONSUMER_KEY_DESERIALIZER_DOC = "Key deserializer to use for the kafka consumers connecting to the source cluster.";
  public static final String CONSUMER_KEY_DESERIALIZER_DEFAULT = ByteArrayDeserializer.class.getName();
  public static final String CONSUMER_VALUE_DESERIALIZER_CONFIG = SOURCE_PREFIX
      .concat(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
  public static final String CONSUMER_VALUE_DESERIALIZER_DOC = "Value deserializer to use for the kafka consumers connecting to the source cluster.";
  public static final String CONSUMER_VALUE_DESERIALIZER_DEFAULT = ByteArrayDeserializer.class.getName();

  public static final String SOURCE_KEY_CONVERTER_CONFIG = "source.key.converter";
  public static final String SOURCE_KEY_CONVERTER_DOC = "";

  public static final String SOURCE_VALUE_CONVERTER_CONFIG = "source.value.converter";
  public static final String SOURCE_VALUE_CONVERTER_DOC = "";

    public static final String SOURCE_KEY_CONVERTER_TOPIC_CONFIG = SOURCE_KEY_CONVERTER_PREFIX.concat("topic");
    public static final String SOURCE_KEY_CONVERTER_TOPIC_DOC = "";
    public static final String SOURCE_KEY_CONVERTER_TOPIC_DEFAULT = "";

    public static final String SOURCE_VALUE_CONVERTER_TOPIC_CONFIG = SOUCRE_VALUE_CONVERTER_PREFIX.concat("topic");
    public static final String SOURCE_VALUE_CONVERTER_TOPIC_DOC = "";
    public static final String SOURCE_VALUE_CONVERTER_TOPIC_DEFAULT = "";

  // Config definition
  public static final ConfigDef CONFIG = new ConfigDef()
      .define(SOURCE_TOPIC_WHITELIST_CONFIG, Type.STRING, SOURCE_TOPIC_WHITELIST_DEFAULT,
          TOPIC_WHITELIST_REGEX_VALIDATOR, Importance.HIGH, SOURCE_TOPIC_WHITELIST_DOC)
      .define(INCLUDE_MESSAGE_HEADERS_CONFIG, Type.BOOLEAN, INCLUDE_MESSAGE_HEADERS_DEFAULT,
          Importance.MEDIUM, INCLUDE_MESSAGE_HEADERS_DOC)
      .define(TOPIC_LIST_TIMEOUT_MS_CONFIG, Type.INT, TOPIC_LIST_TIMEOUT_MS_DEFAULT,
          Importance.LOW,TOPIC_LIST_TIMEOUT_MS_DOC)
      .define(TOPIC_LIST_POLL_INTERVAL_MS_CONFIG, Type.INT, TOPIC_LIST_POLL_INTERVAL_MS_DEFAULT,
          Importance.MEDIUM, TOPIC_LIST_POLL_INTERVAL_MS_DOC)
      .define(RECONFIGURE_TASKS_ON_LEADER_CHANGE_CONFIG, Type.BOOLEAN, RECONFIGURE_TASKS_ON_LEADER_CHANGE_DEFAULT,
          Importance.MEDIUM, RECONFIGURE_TASKS_ON_LEADER_CHANGE_DOC)
      .define(POLL_LOOP_TIMEOUT_MS_CONFIG, Type.INT, POLL_LOOP_TIMEOUT_MS_DEFAULT,
          Importance.LOW, POLL_LOOP_TIMEOUT_MS_DOC)
      .define(MAX_SHUTDOWN_WAIT_MS_CONFIG, Type.INT, MAX_SHUTDOWN_WAIT_MS_DEFAULT,
          Importance.LOW, MAX_SHUTDOWN_WAIT_MS_DOC)
      .define(SOURCE_BOOTSTRAP_SERVERS_CONFIG, Type.LIST, SOURCE_BOOTSTRAP_SERVERS_DEFAULT, NON_EMPTY_LIST_VALIDATOR,
          Importance.HIGH, SOURCE_BOOTSTRAP_SERVERS_DOC)
      .define(CONSUMER_MAX_POLL_RECORDS_CONFIG, Type.INT, CONSUMER_MAX_POLL_RECORDS_DEFAULT,
          Importance.LOW, CONSUMER_MAX_POLL_RECORDS_DOC)
      .define(CONSUMER_AUTO_OFFSET_RESET_CONFIG, Type.STRING, CONSUMER_AUTO_OFFSET_RESET_DEFAULT, CONSUMER_AUTO_OFFSET_RESET_VALIDATOR,
          Importance.MEDIUM, CONSUMER_AUTO_OFFSET_RESET_DOC)
      .define(CONSUMER_KEY_DESERIALIZER_CONFIG, Type.STRING, CONSUMER_KEY_DESERIALIZER_DEFAULT,
          Importance.LOW, CONSUMER_KEY_DESERIALIZER_DOC)
      .define(CONSUMER_VALUE_DESERIALIZER_CONFIG, Type.STRING, CONSUMER_VALUE_DESERIALIZER_DEFAULT,
          Importance.LOW, CONSUMER_VALUE_DESERIALIZER_DOC)
      .define(SOURCE_KEY_CONVERTER_CONFIG, Type.STRING,
          Importance.HIGH, SOURCE_KEY_CONVERTER_DOC)
      .define(SOURCE_VALUE_CONVERTER_CONFIG, Type.STRING,
          Importance.HIGH, SOURCE_VALUE_CONVERTER_DOC)
      .define(SOURCE_KEY_CONVERTER_TOPIC_CONFIG, Type.STRING, SOURCE_KEY_CONVERTER_TOPIC_DEFAULT,
          Importance.HIGH, SOURCE_KEY_CONVERTER_TOPIC_DOC)
      .define(SOURCE_VALUE_CONVERTER_TOPIC_CONFIG, Type.STRING, SOURCE_VALUE_CONVERTER_TOPIC_DEFAULT,
          Importance.HIGH, SOURCE_VALUE_CONVERTER_TOPIC_DOC);

  public KafkaSourceConnectorConfig(Map<String, String> props) {
    super(CONFIG, props);
  }

  // Returns all values with a specified prefix with the prefix stripped from the
  // key
  public Map<String, Object> allWithPrefix(String prefix) {
    return allWithPrefix(prefix, true);
  }

  // Returns all values with a specified prefix with the prefix stripped from the
  // key if desired
  // Original input is set first, then overwritten (if applicable) with the parsed
  // values
  public Map<String, Object> allWithPrefix(String prefix, boolean stripPrefix) {
    Map<String, Object> result = originalsWithPrefix(prefix, stripPrefix);
    for (Map.Entry<String, ?> entry : values().entrySet()) {
      if (entry.getKey().startsWith(prefix) && entry.getKey().length() > prefix.length()) {
        if (stripPrefix)
          result.put(entry.getKey().substring(prefix.length()), entry.getValue());
        else
          result.put(entry.getKey(), entry.getValue());
      }
    }
    return result;
  }

  // Returns all values (part of definition or original strings) as strings so
  // they can be used with functions accepting Map<String,String> configs
  public Map<String, String> allAsStrings() {
    Map<String, String> result = new HashMap<>();
    result.put(INCLUDE_MESSAGE_HEADERS_CONFIG, String.valueOf(getBoolean(INCLUDE_MESSAGE_HEADERS_CONFIG)));
    result.put(TOPIC_LIST_TIMEOUT_MS_CONFIG, String.valueOf(getInt(TOPIC_LIST_TIMEOUT_MS_CONFIG)));
    result.put(TOPIC_LIST_POLL_INTERVAL_MS_CONFIG, String.valueOf(getInt(TOPIC_LIST_POLL_INTERVAL_MS_CONFIG)));
    result.put(RECONFIGURE_TASKS_ON_LEADER_CHANGE_CONFIG, String.valueOf(getBoolean(RECONFIGURE_TASKS_ON_LEADER_CHANGE_CONFIG)));
    result.put(POLL_LOOP_TIMEOUT_MS_CONFIG, String.valueOf(getInt(POLL_LOOP_TIMEOUT_MS_CONFIG)));
    result.put(MAX_SHUTDOWN_WAIT_MS_CONFIG, String.valueOf(getInt(MAX_SHUTDOWN_WAIT_MS_CONFIG)));
    result.put(CONSUMER_MAX_POLL_RECORDS_CONFIG, String.valueOf(getInt(CONSUMER_MAX_POLL_RECORDS_CONFIG)));
    result.put(CONSUMER_AUTO_OFFSET_RESET_CONFIG, getString(CONSUMER_AUTO_OFFSET_RESET_CONFIG));
    result.put(CONSUMER_KEY_DESERIALIZER_CONFIG, getString(CONSUMER_KEY_DESERIALIZER_CONFIG));
    result.put(CONSUMER_VALUE_DESERIALIZER_CONFIG, getString(CONSUMER_VALUE_DESERIALIZER_CONFIG));
    result.putAll(originalsStrings()); // Will set any values without defaults and will capture additional configs like
                                       // consumer settings if supplied
    return result;
  }

  // Return a Properties Object that can be passed to AdminClient.create to
  // configure a Kafka AdminClient instance
  public Properties getAdminClientProperties() {
    Properties adminClientProps = new Properties();
    // By Default use any settings under SOURCE_PREFIX
    adminClientProps.putAll(allWithPrefix(SOURCE_PREFIX));
    // But override with anything under ADMIN_CLIENT_PREFIX
    adminClientProps.putAll(allWithPrefix(ADMIN_CLIENT_PREFIX));
    return adminClientProps;
  }

  // Return a Properties Object that can be passed to KafkaConsumer
  public Properties getKafkaConsumerProperties() {
    Properties kafkaConsumerProps = new Properties();
    // By Default use any settings under SOURCE_PREFIX
    kafkaConsumerProps.putAll(allWithPrefix(SOURCE_PREFIX));
    // But override with anything under CONSUMER_PREFIX
    kafkaConsumerProps.putAll(allWithPrefix(CONSUMER_PREFIX));
    return kafkaConsumerProps;
  }

  // Return a Properties Object that can be passed to org.apache.kafka.connect.storage.Converter
    public Map<String, Object> getSourceKeyConverterProperties() {
      return allWithPrefix(SOURCE_KEY_CONVERTER_PREFIX, true);
  }

  // Return a Properties Object that can be passed to org.apache.kafka.connect.storage.Converter
    public Map<String, Object> getSourceValueConverterProperties() {
      return allWithPrefix(SOUCRE_VALUE_CONVERTER_PREFIX, true);
  }

  public Pattern getTopicWhitelistPattern() {
    return getTopicWhitelistPattern(getString(SOURCE_TOPIC_WHITELIST_CONFIG));
  }

  // Returns a java regex pattern that can be used to match kafka topics
  private static Pattern getTopicWhitelistPattern(String rawRegex) {
    String regex = rawRegex.trim().replace(',', '|').replace(" ", "").replaceAll("^[\"']+", "").replaceAll("[\"']+$",
        ""); // property files may bring quotes
    try {
      return Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      throw new ConfigException(regex + " is an invalid regex for CONFIG " + SOURCE_TOPIC_WHITELIST_CONFIG);
    }
  }

}
