/**
 * Copyright 2008 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.filter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import junit.framework.Assert;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseClusterTestCase;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HScannerInterface;
import org.apache.hadoop.hbase.HStoreKey;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.HBaseAdmin;
import org.apache.hadoop.hbase.HTable;
import org.apache.hadoop.io.Text;

/** Test regexp filters HBASE-476 */
public class TestRowFilterAfterWrite extends HBaseClusterTestCase {

  @SuppressWarnings("hiding")
  private static final Log LOG = LogFactory.getLog(TestRowFilterAfterWrite.class.getName());

  static final String TABLE_NAME = "TestTable";
  static final String FAMILY = "C:";
  static final String COLUMN1 = FAMILY + "col1";
  static final Text TEXT_COLUMN1 = new Text(COLUMN1);
  static final String COLUMN2 = FAMILY + "col2";
  static final Text TEXT_COLUMN2 = new Text(COLUMN2);

  private static final Text[] columns = {
    TEXT_COLUMN1, TEXT_COLUMN2
  };

  private static final int NUM_ROWS = 10;
  private static final int VALUE_SIZE = 1000;
  private static final byte[] VALUE = new byte[VALUE_SIZE];
  private static final int COL_2_SIZE = 5;
  private static final int KEY_SIZE = 9;
  private static final int NUM_REWRITES = 10;
  static {
    Arrays.fill(VALUE, (byte) 'a');
  }

  /** constructor */
  public TestRowFilterAfterWrite() {
    super();

    // Make sure the cache gets flushed so we get multiple stores
    conf.setInt("hbase.hregion.memcache.flush.size", (NUM_ROWS * (VALUE_SIZE + COL_2_SIZE + KEY_SIZE)));
    LOG.info("memcach flush : " + conf.get("hbase.hregion.memcache.flush.size"));
    conf.setInt("hbase.regionserver.optionalcacheflushinterval", 100000000);
    // Avoid compaction to keep multiple stores
    conf.setInt("hbase.hstore.compactionThreshold", 10000);

    // Make lease timeout longer, lease checks less frequent
    conf.setInt("hbase.master.lease.period", 10 * 1000);
    conf.setInt("hbase.master.lease.thread.wakefrequency", 5 * 1000);

    // For debugging
    conf.setInt("hbase.regionserver.lease.period", 20 * 60 * 1000);
    conf.setInt("ipc.client.timeout", 20 * 60 * 1000);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void tearDown() throws Exception {
    super.tearDown();
  }

  /**
   * Test hbase mapreduce jobs against single region and multi-region tables.
   * 
   * @throws IOException
   * @throws InterruptedException
   */
  public void testAfterWrite() throws IOException, InterruptedException {
    singleTableTest();
  }

  /*
   * Test against a single region. @throws IOException
   */
  private void singleTableTest() throws IOException, InterruptedException {
    HTableDescriptor desc = new HTableDescriptor(TABLE_NAME);
    desc.addFamily(new HColumnDescriptor(FAMILY));

    // Create a table.
    HBaseAdmin admin = new HBaseAdmin(this.conf);
    admin.createTable(desc);

    // insert some data into the test table
    HTable table = new HTable(conf, new Text(TABLE_NAME));

    for (int i = 0; i < NUM_ROWS; i++) {
      long id = table.startUpdate(new Text("row_" + String.format("%1$05d", i)));

      table.put(id, TEXT_COLUMN1, VALUE);
      table.put(id, TEXT_COLUMN2, String.format("%1$05d", i).getBytes());
      table.commit(id);
    }

    // LOG.info("Print table contents using scanner before map/reduce for " + TABLE_NAME);
    // scanTable(TABLE_NAME, false);
    // LOG.info("Print table contents using scanner+filter before map/reduce for " + TABLE_NAME);
    // scanTableWithRowFilter(TABLE_NAME, false);

    // Do some identity write operations on one column of the data.
    for (int n = 0; n < NUM_REWRITES; n++) {
      for (int i = 0; i < NUM_ROWS; i++) {
        long id = table.startUpdate(new Text("row_" + String.format("%1$05d", i)));

        table.put(id, TEXT_COLUMN2, String.format("%1$05d", i).getBytes());
        table.commit(id);
      }
    }

    // Wait for the flush to happen
    LOG.info("Waiting, for flushes to complete");
    Thread.sleep(5 * 1000);
    // Wait for the flush to happen
    LOG.info("Done. No flush should happen after this");

    // Do another round so to populate the mem cache
    for (int i = 0; i < NUM_ROWS; i++) {
      long id = table.startUpdate(new Text("row_" + String.format("%1$05d", i)));

      table.put(id, TEXT_COLUMN2, String.format("%1$05d", i).getBytes());
      table.commit(id);
    }

    LOG.info("Print table contents using scanner after map/reduce for " + TABLE_NAME);
    scanTable(TABLE_NAME, true);
    LOG.info("Print table contents using scanner+filter after map/reduce for " + TABLE_NAME);
    scanTableWithRowFilter(TABLE_NAME, true);
  }

  private void scanTable(final String tableName, final boolean printValues) throws IOException {
    HTable table = new HTable(conf, new Text(tableName));

    HScannerInterface scanner = table.obtainScanner(columns, HConstants.EMPTY_START_ROW);
    int numFound = doScan(scanner, printValues);
    Assert.assertEquals(NUM_ROWS, numFound);
  }

  private void scanTableWithRowFilter(final String tableName, final boolean printValues) throws IOException {
    HTable table = new HTable(conf, new Text(tableName));
    Map<Text, byte[]> columnMap = new HashMap<Text, byte[]>();
    columnMap.put(TEXT_COLUMN1, VALUE);
    RegExpRowFilter filter = new RegExpRowFilter(null, columnMap);
    HScannerInterface scanner = table.obtainScanner(columns, HConstants.EMPTY_START_ROW, filter);
    int numFound = doScan(scanner, printValues);
    Assert.assertEquals(NUM_ROWS, numFound);
  }

  private int doScan(final HScannerInterface scanner, final boolean printValues) throws IOException {
    {
      int count = 0;

      try {
        HStoreKey key = new HStoreKey();
        TreeMap<Text, byte[]> results = new TreeMap<Text, byte[]>();
        while (scanner.next(key, results)) {
          if (printValues) {
            LOG.info("row: " + key.getRow());

            for (Map.Entry<Text, byte[]> e : results.entrySet()) {
              LOG.info(" column: " + e.getKey() + " value: "
                  + new String(e.getValue(), HConstants.UTF8_ENCODING));
            }
          }
          count++;
        }

      } finally {
        scanner.close();
      }
      return count;
    }
  }
}
