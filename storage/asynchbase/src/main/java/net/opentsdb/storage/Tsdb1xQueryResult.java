// This file is part of OpenTSDB.
// Copyright (C) 2018-2020  The OpenTSDB Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.opentsdb.storage;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Map;

import net.opentsdb.rollup.RollupInterval;
import org.hbase.async.KeyValue;

import com.google.common.collect.Maps;

import net.opentsdb.query.QueryNode;
import net.opentsdb.rollup.DefaultRollupInterval;
import net.opentsdb.storage.schemas.tsdb1x.NumericRowSeq;
import net.opentsdb.storage.schemas.tsdb1x.NumericSummaryRowSeq;
import net.opentsdb.storage.schemas.tsdb1x.RowSeq;
import net.opentsdb.storage.schemas.tsdb1x.Schema;

/**
 * A query result generated by the Tsdb1xQueryNode
 * 
 * @since 3.0
 */
public class Tsdb1xQueryResult extends 
  net.opentsdb.storage.schemas.tsdb1x.Tsdb1xQueryResult {

  private static final byte NUMERIC_TYPE = (byte) 1;
  private static final byte NUMERIC_PREFIX = (byte) 0;
  
  /**
   * Default ctor.
   * @param sequence_id The sequence ID.
   * @param node The non-null parent node.
   * @param schema The non-null schema.
   */
  public Tsdb1xQueryResult(final long sequence_id, 
                           final QueryNode node, 
                           final Schema schema) {
    super(sequence_id, node, schema);
  }
  
  /**
   * Parses a row for results. Since numerics are the most prevalent we
   * have a dedicated rowSeq for those (if we're told to fetch em). For 
   * other types we'll build out a map. After the row is finished we 
   * call {@link RowSeq#dedupe(boolean, boolean)} on each one then
   * pass it to the seq handler.
   * Note: Since it's the fast path we don't check for nulls/empty in
   * the row.
   * 
   * @param row A non-null and non-empty list of columns.
   * @param interval An optional interval, may be null.
   */
  public void decode(final ArrayList<KeyValue> row,
                     final RollupInterval interval) {
    final long base_timestamp = schema.baseTimestamp(row.get(0).key());
    final long hash = schema.getTSUIDHash(row.get(0).key());
    final RowSeq numerics;
    if (((Tsdb1xHBaseQueryNode) node).fetchDataType(NUMERIC_TYPE)) {
      if (interval != null) {
        numerics = new NumericSummaryRowSeq(base_timestamp, interval);
      } else {
        numerics = new NumericRowSeq(base_timestamp);
      }
    } else {
      numerics = null;
    }
    Map<Byte, RowSeq> row_sequences = null;
    
    for (final KeyValue kv : row) {
      if (interval == null && (kv.qualifier().length & 1) == 0) {
        // it's a NumericDataType
        if (!((Tsdb1xHBaseQueryNode) node).fetchDataType(NUMERIC_TYPE)) {
          // filter doesn't want #'s
          // TODO - dropped counters
          continue;
        }
        numerics.addColumn(NUMERIC_PREFIX, kv.qualifier(), kv.value());
      } else if (interval == null) {
        final byte prefix = kv.qualifier()[0];
        if (prefix == Schema.APPENDS_PREFIX) {
          if (!((Tsdb1xHBaseQueryNode) node).fetchDataType((byte) 1)) {
            // filter doesn't want #'s
            continue;
          } else {
            numerics.addColumn(Schema.APPENDS_PREFIX, kv.qualifier(), 
                kv.value());
          }
        } else if (((Tsdb1xHBaseQueryNode) node).fetchDataType(prefix)) {
          if (row_sequences == null) {
            row_sequences = Maps.newHashMapWithExpectedSize(1);
          }
          RowSeq sequence = row_sequences.get(prefix);
          if (sequence == null) {
            sequence = schema.newRowSeq(prefix, base_timestamp);
            if (sequence == null) {
              // TODO - determine how to handle non-codec'd data.
              continue;
            }
            row_sequences.put(prefix, sequence);
          }
          
          sequence.addColumn(prefix, kv.qualifier(), kv.value());
        } else {
          // TODO else count dropped data
        }
      } else {
        // Only numerics are rolled up right now. And we shouldn't have
        // a rollup query if the user doesn't want rolled-up data.
        numerics.addColumn(NUMERIC_PREFIX, kv.qualifier(), kv.value());
      }
    }
    
    // TODO - we need to find a way to handle the dedupe ONCE in case we have 
    //        multiple dps across multiple calls to a scanner!
    // TODO - handle the write-back of the deduped columns.
    if (numerics != null) {
      final ChronoUnit resolution = numerics.dedupe(node.pipelineContext().tsdb(), 
          keep_earliest, reversed);
      addSequence(hash, row.get(0).key(), numerics, resolution);
    }
    
    if (row_sequences != null) {
      for (final RowSeq sequence : row_sequences.values()) {
        final ChronoUnit resolution = sequence.dedupe(node.pipelineContext().tsdb(), 
            keep_earliest, reversed);
        addSequence(hash, row.get(0).key(), sequence, resolution);
      }
    }
  }
  
}