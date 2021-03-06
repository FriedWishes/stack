/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.usergrid.persistence.cassandra;

import com.usergrid.count.Batcher;
import com.usergrid.count.common.Count;
import me.prettyprint.cassandra.serializers.*;
import me.prettyprint.hector.api.beans.HCounterColumn;
import me.prettyprint.hector.api.mutation.Mutator;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usergrid.mq.Message;
import org.usergrid.mq.cassandra.QueuesCF;
import org.usergrid.persistence.CounterResolution;
import org.usergrid.persistence.entities.Event;
import org.usergrid.utils.ConversionUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import static me.prettyprint.hector.api.factory.HFactory.createColumn;
import static me.prettyprint.hector.api.factory.HFactory.createCounterColumn;
import static org.usergrid.persistence.Schema.DICTIONARY_COUNTERS;
import static org.usergrid.persistence.Schema.PROPERTY_EMAIL;
import static org.usergrid.persistence.cassandra.ApplicationCF.*;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.addInsertToMutator;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.key;
import static org.usergrid.utils.ConversionUtils.bytebuffer;

public class CounterUtils {

	public static final Logger logger = LoggerFactory.getLogger(CounterUtils.class);

	public static final LongSerializer le = new LongSerializer();
	public static final StringSerializer se = new StringSerializer();
	public static final ByteBufferSerializer be = new ByteBufferSerializer();
	public static final UUIDSerializer ue = new UUIDSerializer();

  private String counterType = "o";

    private Batcher batcher;

    public void setBatcher(Batcher batcher) {
        this.batcher = batcher;
    }

  /**
   * Set the type to 'new' ("n"), 'parallel' ("p"), 'old' ("o" - the default)
   * If not one of the above, do nothing
   * @param counterType
   */
  public void setCounterType(String counterType) {
    if ( StringUtils.equals(counterType,"n") || StringUtils.equals(counterType, "p") || StringUtils.equals(counterType,"o") ) {
      this.counterType = counterType;
    }
  }

    public static class AggregateCounterSelection {
		String name;
		UUID userId;
		UUID groupId;
		UUID queueId;
		String category;

		public AggregateCounterSelection(String name, UUID userId,
				UUID groupId, UUID queueId, String category) {
			this.name = name.toLowerCase();
			this.userId = userId;
			this.groupId = groupId;
			this.queueId = queueId;
			this.category = category;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public UUID getUserId() {
			return userId;
		}

		public void setUserId(UUID userId) {
			this.userId = userId;
		}

		public UUID getGroupId() {
			return groupId;
		}

		public void setGroupId(UUID groupId) {
			this.groupId = groupId;
		}

		public UUID getQueueId() {
			return queueId;
		}

		public void setQueueId(UUID queueId) {
			this.queueId = queueId;
		}

		public String getCategory() {
			return category;
		}

		public void setCategory(String category) {
			this.category = category;
		}

		public String getRow(CounterResolution resolution) {
			return name + ":" + (userId != null ? userId.toString() : "*")
					+ ":" + (groupId != null ? groupId.toString() : "*") + ":"
					+ (queueId != null ? queueId.toString() : "*") + ":"
					+ (category != null ? category : "*") + ":"
					+ resolution.name();
		}
	}

	public void addEventCounterMutations(Mutator<ByteBuffer> m,
			UUID applicationId, Event event, long timestamp) {
		if (event.getCounters() != null) {
			for (Entry<String, Integer> value : event.getCounters().entrySet()) {
				batchIncrementAggregateCounters(m, applicationId,
						event.getUser(), event.getGroup(), null,
						event.getCategory(), value.getKey().toLowerCase(),
						value.getValue(), event.getTimestamp(), timestamp);
			}
		}
	}

	public void addMessageCounterMutations(Mutator<ByteBuffer> m,
			UUID applicationId, UUID queueId, Message msg, long timestamp) {
		if (msg.getCounters() != null) {
			for (Entry<String, Integer> value : msg.getCounters().entrySet()) {
				batchIncrementAggregateCounters(m, applicationId, null, null,
						queueId, msg.getCategory(), value.getKey()
								.toLowerCase(), value.getValue(),
						msg.getTimestamp(), timestamp);
			}
		}
	}

	public void batchIncrementAggregateCounters(Mutator<ByteBuffer> m,
			UUID applicationId, UUID userId, UUID groupId, UUID queueId,
			String category, Map<String, Long> counters, long timestamp) {
		if (counters != null) {
			for (Entry<String, Long> value : counters.entrySet()) {
				batchIncrementAggregateCounters(m, applicationId, userId,
						groupId, queueId, category, value.getKey()
								.toLowerCase(), value.getValue(), timestamp);
			}
		}
	}

	public void batchIncrementAggregateCounters(Mutator<ByteBuffer> m,
			UUID applicationId, UUID userId, UUID groupId, UUID queueId,
			String category, String name, long value, long cassandraTimestamp) {
		batchIncrementAggregateCounters(m, applicationId, userId, groupId,
				queueId, category, name, value, cassandraTimestamp / 1000,
				cassandraTimestamp);
	}

	private void batchIncrementAggregateCounters(Mutator<ByteBuffer> m,
			UUID applicationId, UUID userId, UUID groupId, UUID queueId,
			String category, String name, long value, long counterTimestamp,
			long cassandraTimestamp) {
		for (CounterResolution resolution : CounterResolution.values()) {
			batchIncrementAggregateCounters(m, userId, groupId, queueId,
					category, resolution, name, value, counterTimestamp, applicationId);
		}
		batchIncrementEntityCounter(m, applicationId, name, value,
				cassandraTimestamp, applicationId);
		if (userId != null) {
			batchIncrementEntityCounter(m, userId, name, value,
					cassandraTimestamp, applicationId);
		}
		if (groupId != null) {
			batchIncrementEntityCounter(m, groupId, name, value,
					cassandraTimestamp, applicationId);
		}
	}

	private void batchIncrementAggregateCounters(Mutator<ByteBuffer> m,
			UUID userId, UUID groupId, UUID queueId, String category,
			CounterResolution resolution, String name, long value,
			long counterTimestamp, UUID applicationId) {

		String[] segments = StringUtils.split(name, '.');
		for (int j = 0; j < segments.length; j++) {
			name = StringUtils.join(segments, '.', 0, j + 1);
			// skip system counter
			if ("system".equals(name)) {
				continue;
			}

			// *:*:*:*
			handleAggregateCounterRow(
					m,
					getAggregateCounterRow(name, null, null, null, null,
							resolution), resolution.round(counterTimestamp),
					value, applicationId);

			for (int i = 0; i < 16; i++) {

				boolean include_user = (i & 0x01) != 0;
				boolean include_group = (i & 0x02) != 0;
				boolean include_queue = (i & 0x04) != 0;
				boolean include_category = (i & 0x08) != 0;

				Object[] parameters = { include_user ? userId : null,
						include_group ? groupId : null,
						include_queue ? queueId : null,
						include_category ? category : null };
				int non_null = 0;
				for (Object p : parameters) {
					if (p != null) {
						non_null++;
					}
				}
				if (non_null > 0) {
					handleAggregateCounterRow(
							m,
							getAggregateCounterRow(name, (UUID) parameters[0],
									(UUID) parameters[1], (UUID) parameters[2],
									(String) parameters[3], resolution),
							resolution.round(counterTimestamp), value, applicationId);
				}
			}

		}

	}

	private void handleAggregateCounterRow(Mutator<ByteBuffer> m,
			String key, long column, long value, UUID applicationId) {
		// logger.info("update counts set " + column + " += " + value
		// + " where key = \"" + key + "\"");
    if ( "o".equals(counterType) || "p".equals(counterType)) {
      if (m != null) {
        HCounterColumn<Long> c = createCounterColumn(column, value, le);
        m.addCounter(bytebuffer(key),
                APPLICATION_AGGREGATE_COUNTERS.toString(), c);
      }
    }
    if ( "n".equals(counterType) || "p".equals(counterType)) {
        // create and add Count
        PrefixedSerializer ps = new PrefixedSerializer(applicationId, UUIDSerializer.get(),
                StringSerializer.get());
        batcher.add(new Count(APPLICATION_AGGREGATE_COUNTERS.toString(), ps.toByteBuffer(key),
                column,
                value));
    }
	}

	public AggregateCounterSelection getAggregateCounterSelection(
			String name, UUID userId, UUID groupId, UUID queueId,
			String category) {
		return new AggregateCounterSelection(name, userId, groupId, queueId,
				category);
	}

	public String getAggregateCounterRow(String name, UUID userId,
			UUID groupId, UUID queueId, String category,
			CounterResolution resolution) {
		return getAggregateCounterSelection(name, userId, groupId, queueId,
				category).getRow(resolution);
	}

	public List<String> getAggregateCounterRows(
			List<AggregateCounterSelection> selections,
			CounterResolution resolution) {
		List<String> keys = new ArrayList<String>();
		for (AggregateCounterSelection selection : selections) {
			keys.add(selection.getRow(resolution));
		}
		return keys;
	}

	public Mutator<ByteBuffer> batchIncrementEntityCounter(
			Mutator<ByteBuffer> m, UUID entityId, String name, Long value,
			long timestamp, UUID applicationId) {
		// logger.info("Incrementing property " + name + " of entity " +
		// entityId
		// + " by " + value);
    if ( "o".equals(counterType) || "p".equals(counterType)) {

      HCounterColumn<String> c = createCounterColumn(name, value);
            m.addCounter(bytebuffer(entityId), ENTITY_COUNTERS.toString(), c);
    }
		addInsertToMutator(m, ENTITY_DICTIONARIES,
				key(entityId, DICTIONARY_COUNTERS), name, null, timestamp);
        // create and send Count
    if ( "n".equals(counterType) || "p".equals(counterType)) {
      PrefixedSerializer ps = new PrefixedSerializer(applicationId, UUIDSerializer.get(),
                            UUIDSerializer.get());
        batcher.add(new Count(ENTITY_COUNTERS.toString(),
                ps.toByteBuffer(entityId),
                name,
                value));
    }
		return m;
	}

	public Mutator<ByteBuffer> batchIncrementEntityCounters(
			Mutator<ByteBuffer> m, UUID entityId, Map<String, Long> values,
			long timestamp, UUID applicationId) {
		for (Entry<String, Long> entry : values.entrySet()) {
			batchIncrementEntityCounter(m, entityId, entry.getKey(),
					entry.getValue(), timestamp, applicationId);
		}
		return m;
	}

	public Mutator<ByteBuffer> batchIncrementEntityCounters(
			Mutator<ByteBuffer> m, Map<UUID, Map<String, Long>> values,
			long timestamp, UUID applicationId) {
		for (Entry<UUID, Map<String, Long>> entry : values.entrySet()) {
			batchIncrementEntityCounters(m, entry.getKey(), entry.getValue(),
					timestamp, applicationId);
		}
		return m;
	}

	public Mutator<ByteBuffer> batchIncrementQueueCounter(
			Mutator<ByteBuffer> m, UUID queueId, String name, long value,
			long timestamp, UUID applicationId) {
		// logger.info("Incrementing property " + name + " of entity " +
		// entityId
		// + " by " + value);
    if ( "o".equals(counterType) || "p".equals(counterType)) {
      HCounterColumn<String> c = createCounterColumn(name, value);
      ByteBuffer keybytes = bytebuffer(queueId);
      m.addCounter(keybytes, QueuesCF.COUNTERS.toString(), c);
    }

		m.addInsertion(
				bytebuffer(key(queueId, DICTIONARY_COUNTERS).toString()),
				QueuesCF.QUEUE_DICTIONARIES.toString(),
				createColumn(name, ByteBuffer.allocate(0), timestamp, se, be));
        // create and send Count
    if ( "n".equals(counterType) || "p".equals(counterType)) {
        PrefixedSerializer ps = new PrefixedSerializer(applicationId, UUIDSerializer.get(),
                                  UUIDSerializer.get());
        batcher.add(new Count(QueuesCF.COUNTERS.toString(),
                ps.toByteBuffer(queueId),
                name,
                value));
    }
		return m;
	}

	public Mutator<ByteBuffer> batchIncrementQueueCounters(
			Mutator<ByteBuffer> m, UUID queueId, Map<String, Long> values,
			long timestamp, UUID applicationId) {
		for (Entry<String, Long> entry : values.entrySet()) {
			batchIncrementQueueCounter(m, queueId, entry.getKey(),
					entry.getValue(), timestamp, applicationId);
		}
		return m;
	}

	public Mutator<ByteBuffer> batchIncrementQueueCounters(
			Mutator<ByteBuffer> m, Map<UUID, Map<String, Long>> values,
			long timestamp, UUID applicationId) {
		for (Entry<UUID, Map<String, Long>> entry : values.entrySet()) {
			batchIncrementQueueCounters(m, entry.getKey(), entry.getValue(),
					timestamp, applicationId);
		}
		return m;
	}

}
