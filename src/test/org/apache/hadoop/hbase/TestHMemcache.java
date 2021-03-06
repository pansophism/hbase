/**
 * Copyright 2007 The Apache Software Foundation
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
package org.apache.hadoop.hbase;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.rmi.UnexpectedException;
import java.util.Map;
import java.util.TreeMap;
import java.util.SortedMap;
import junit.framework.TestCase;

import org.apache.hadoop.io.Text;

/** memcache test case */
public class TestHMemcache extends TestCase {
  
  private HStore.Memcache hmemcache;

  private static final int ROW_COUNT = 3;

  private static final int COLUMNS_COUNT = 3;
  
  private static final String COLUMN_FAMILY = "column";

  /** {@inheritDoc} */
  @Override
  public void setUp() throws Exception {
    super.setUp();
    this.hmemcache = new HStore.Memcache();
  }

  private Text getRowName(final int index) {
    return new Text("row" + Integer.toString(index));
  }

  private Text getColumnName(final int rowIndex, final int colIndex) {
    return new Text(COLUMN_FAMILY + ":" + Integer.toString(rowIndex) + ";" +
        Integer.toString(colIndex));
  }

  /**
   * Adds {@link #ROW_COUNT} rows and {@link #COLUMNS_COUNT}
   * @param hmc Instance to add rows to.
   */
  private void addRows(final HStore.Memcache hmc)
  throws UnsupportedEncodingException {
    for (int i = 0; i < ROW_COUNT; i++) {
      long timestamp = System.currentTimeMillis();
      for (int ii = 0; ii < COLUMNS_COUNT; ii++) {
        Text k = getColumnName(i, ii);
        hmc.add(new HStoreKey(getRowName(i), k, timestamp),
            k.toString().getBytes(HConstants.UTF8_ENCODING));
      }
    }
  }

  private void runSnapshot(final HStore.Memcache hmc)
  throws UnexpectedException {
    // Save off old state.
    int oldHistorySize = hmc.getSnapshot().size();
    hmc.snapshot();
    SortedMap<HStoreKey, byte[]> ss = hmc.getSnapshot();
    // Make some assertions about what just happened.
    assertTrue("History size has not increased", oldHistorySize < ss.size());
    hmc.clearSnapshot(ss);
  }

  /** 
   * Test memcache snapshots
   * @throws IOException
   */
  public void testSnapshotting() throws IOException {
    final int snapshotCount = 5;
    // Add some rows, run a snapshot. Do it a few times.
    for (int i = 0; i < snapshotCount; i++) {
      addRows(this.hmemcache);
      runSnapshot(this.hmemcache);
      SortedMap<HStoreKey, byte[]> ss = this.hmemcache.getSnapshot();
      assertEquals("History not being cleared", 0, ss.size());
    }
  }
  
  private void isExpectedRow(final int rowIndex, TreeMap<Text, byte []> row)
  throws UnsupportedEncodingException {
    int i = 0;
    for (Text colname: row.keySet()) {
      String expectedColname = getColumnName(rowIndex, i++).toString();
      String colnameStr = colname.toString();
      assertEquals("Column name", colnameStr, expectedColname);
      // Value is column name as bytes.  Usually result is
      // 100 bytes in size at least. This is the default size
      // for BytesWriteable.  For comparison, comvert bytes to
      // String and trim to remove trailing null bytes.
      byte [] value = row.get(colname);
      String colvalueStr = new String(value, HConstants.UTF8_ENCODING).trim();
      assertEquals("Content", colnameStr, colvalueStr);
    }
  }

  /** Test getFull from memcache
   * @throws UnsupportedEncodingException
   */
  public void testGetFull() throws UnsupportedEncodingException {
    addRows(this.hmemcache);
    for (int i = 0; i < ROW_COUNT; i++) {
      HStoreKey hsk = new HStoreKey(getRowName(i));
      TreeMap<Text, byte []> all = new TreeMap<Text, byte[]>();
      Map<Text, Long> deletes = new TreeMap<Text, Long>();
      this.hmemcache.getFull(hsk, deletes, all);
      isExpectedRow(i, all);
    }
  }

  /** Test getNextRow from memcache
   * @throws UnsupportedEncodingException 
   */
  public void testGetNextRow() throws UnsupportedEncodingException {
    addRows(this.hmemcache);
    Text closestToEmpty = this.hmemcache.getNextRow(HConstants.EMPTY_TEXT);
    assertEquals(closestToEmpty, getRowName(0));
    for (int i = 0; i < ROW_COUNT; i++) {
      Text nr = this.hmemcache.getNextRow(getRowName(i));
      if (i + 1 == ROW_COUNT) {
        assertEquals(nr, null);
      } else {
        assertEquals(nr, getRowName(i + 1));
      }
    }
  }

  /** Test getClosest from memcache
   * @throws UnsupportedEncodingException 
   */
  public void testGetClosest() throws UnsupportedEncodingException {
    addRows(this.hmemcache);
    Text closestToEmpty = this.hmemcache.getNextRow(HConstants.EMPTY_TEXT);
    assertEquals(closestToEmpty, getRowName(0));
    for (int i = 0; i < ROW_COUNT; i++) {
      Text nr = this.hmemcache.getNextRow(getRowName(i));
      if (i + 1 == ROW_COUNT) {
        assertEquals(nr, null);
      } else {
        assertEquals(nr, getRowName(i + 1));
      }
    }
  }

  /**
   * Test memcache scanner
   * @throws IOException
   */
  public void testScanner() throws IOException {
    addRows(this.hmemcache);
    long timestamp = System.currentTimeMillis();
    Text [] cols = new Text[COLUMNS_COUNT * ROW_COUNT];
    for (int i = 0; i < ROW_COUNT; i++) {
      for (int ii = 0; ii < COLUMNS_COUNT; ii++) {
        cols[(ii + (i * COLUMNS_COUNT))] = getColumnName(i, ii);
      }
    }
    HInternalScannerInterface scanner =
      this.hmemcache.getScanner(timestamp, cols, new Text());
    HStoreKey key = new HStoreKey();
    TreeMap<Text, byte []> results = new TreeMap<Text, byte []>();
    for (int i = 0; scanner.next(key, results); i++) {
      assertTrue("Row name",
          key.toString().startsWith(getRowName(i).toString()));
      assertEquals("Count of columns", COLUMNS_COUNT,
          results.size());
      TreeMap<Text, byte []> row = new TreeMap<Text, byte []>();
      for(Map.Entry<Text, byte []> e: results.entrySet() ) {
        row.put(e.getKey(), e.getValue());
      }
      isExpectedRow(i, row);
      // Clear out set.  Otherwise row results accumulate.
      results.clear();
    }
  }
  
  /** For HBASE-514 **/
  public void testGetRowKeyAtOrBefore() {
    // set up some test data
    Text t10 = new Text("010");
    Text t20 = new Text("020");
    Text t30 = new Text("030");
    Text t35 = new Text("035");
    Text t40 = new Text("040");
    
    hmemcache.add(getHSKForRow(t10), "t10 bytes".getBytes());
    hmemcache.add(getHSKForRow(t20), "t20 bytes".getBytes());
    hmemcache.add(getHSKForRow(t30), "t30 bytes".getBytes());
    // write a delete in there to see if things still work ok
    hmemcache.add(getHSKForRow(t35), HLogEdit.deleteBytes.get());
    hmemcache.add(getHSKForRow(t40), "t40 bytes".getBytes());
    
    SortedMap<HStoreKey, Long> results = null;
    
    // try finding "015"
    results = new TreeMap<HStoreKey, Long>();
    Text t15 = new Text("015");
    hmemcache.getRowKeyAtOrBefore(t15, results);
    assertEquals(t10, results.lastKey().getRow());
    
    // try "020", we should get that row exactly
    results = new TreeMap<HStoreKey, Long>();
    hmemcache.getRowKeyAtOrBefore(t20, results);
    assertEquals(t20, results.lastKey().getRow());

    // try "038", should skip the deleted "035" and give "030"
    results = new TreeMap<HStoreKey, Long>();
    Text t38 = new Text("038");
    hmemcache.getRowKeyAtOrBefore(t38, results);
    assertEquals(t30, results.lastKey().getRow());

    // try "050", should get stuff from "040"
    results = new TreeMap<HStoreKey, Long>();
    Text t50 = new Text("050");
    hmemcache.getRowKeyAtOrBefore(t50, results);
    assertEquals(t40, results.lastKey().getRow());
  }
  
  private HStoreKey getHSKForRow(Text row) {
    return new HStoreKey(row, new Text("test_col:"), HConstants.LATEST_TIMESTAMP);
  }

  /**
   * Test memcache scanner scanning cached rows, HBASE-686
   * @throws IOException
   */
  public void testScanner_686() throws IOException {
    addRows(this.hmemcache);
    long timestamp = System.currentTimeMillis();
    Text[] cols = new Text[COLUMNS_COUNT * ROW_COUNT];
    for (int i = 0; i < ROW_COUNT; i++) {
      for (int ii = 0; ii < COLUMNS_COUNT; ii++) {
        cols[(ii + (i * COLUMNS_COUNT))] = getColumnName(i, ii);
      }
    }
    //starting from each row, validate results should contain the starting row
    for (int startRowId = 0; startRowId < ROW_COUNT; startRowId++) {
      HInternalScannerInterface scanner = this.hmemcache.getScanner(timestamp,
          cols, new Text(getRowName(startRowId)));
      HStoreKey key = new HStoreKey();
      TreeMap<Text, byte[]> results = new TreeMap<Text, byte[]>();
      for (int i = 0; scanner.next(key, results); i++) {
        int rowId = startRowId + i;
        assertTrue("Row name",
            key.toString().startsWith(getRowName(rowId).toString()));
        assertEquals("Count of columns", COLUMNS_COUNT, results.size());
        TreeMap<Text, byte[]> row = new TreeMap<Text, byte[]>();
        for (Map.Entry<Text, byte[]> e : results.entrySet()) {
          row.put(e.getKey(), e.getValue());
        }
        isExpectedRow(rowId, row);
        // Clear out set.  Otherwise row results accumulate.
        results.clear();
      }
    }
  }

}