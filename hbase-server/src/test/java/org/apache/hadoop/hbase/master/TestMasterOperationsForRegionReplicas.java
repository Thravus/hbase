/**
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
package org.apache.hadoop.hbase.master;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ClusterMetrics.Option;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MetaTableAccessor;
import org.apache.hadoop.hbase.MetaTableAccessor.Visitor;
import org.apache.hadoop.hbase.RegionLocations;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionReplicaUtil;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.testclassification.MasterTests;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Category({MasterTests.class, MediumTests.class})
public class TestMasterOperationsForRegionReplicas {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
      HBaseClassTestRule.forClass(TestMasterOperationsForRegionReplicas.class);

  private static final Logger LOG = LoggerFactory.getLogger(TestRegionPlacement.class);
  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static Connection CONNECTION = null;
  private static Admin ADMIN;
  private static int numSlaves = 2;
  private static Configuration conf;

  @Rule
  public TestName name = new TestName();

  @BeforeClass
  public static void setupBeforeClass() throws Exception {
    conf = TEST_UTIL.getConfiguration();
    conf.setBoolean("hbase.tests.use.shortcircuit.reads", false);
    TEST_UTIL.startMiniCluster(numSlaves);
    CONNECTION = ConnectionFactory.createConnection(TEST_UTIL.getConfiguration());
    ADMIN = CONNECTION.getAdmin();
    while(ADMIN.getClusterMetrics(EnumSet.of(Option.LIVE_SERVERS))
               .getLiveServerMetrics().size() < numSlaves) {
      Thread.sleep(100);
    }
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    if (ADMIN != null) ADMIN.close();
    if (CONNECTION != null && !CONNECTION.isClosed()) CONNECTION.close();
    TEST_UTIL.shutdownMiniCluster();
  }

  @Test
  public void testCreateTableWithSingleReplica() throws Exception {
    final int numRegions = 3;
    final int numReplica = 1;
    final TableName tableName = TableName.valueOf(name.getMethodName());
    try {
      HTableDescriptor desc = new HTableDescriptor(tableName);
      desc.setRegionReplication(numReplica);
      desc.addFamily(new HColumnDescriptor("family"));
      ADMIN.createTable(desc, Bytes.toBytes("A"), Bytes.toBytes("Z"), numRegions);

      validateNumberOfRowsInMeta(tableName, numRegions, ADMIN.getConnection());
      List<RegionInfo> hris = MetaTableAccessor.getTableRegions(
        ADMIN.getConnection(), tableName);
      assert(hris.size() == numRegions * numReplica);
    } finally {
      ADMIN.disableTable(tableName);
      ADMIN.deleteTable(tableName);
    }
  }

  @Test
  public void testCreateTableWithMultipleReplicas() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    final int numRegions = 3;
    final int numReplica = 2;
    try {
      HTableDescriptor desc = new HTableDescriptor(tableName);
      desc.setRegionReplication(numReplica);
      desc.addFamily(new HColumnDescriptor("family"));
      ADMIN.createTable(desc, Bytes.toBytes("A"), Bytes.toBytes("Z"), numRegions);
      TEST_UTIL.waitTableEnabled(tableName);
      validateNumberOfRowsInMeta(tableName, numRegions, ADMIN.getConnection());

      List<RegionInfo> hris = MetaTableAccessor.getTableRegions(ADMIN.getConnection(), tableName);
      assert(hris.size() == numRegions * numReplica);
      // check that the master created expected number of RegionState objects
      for (int i = 0; i < numRegions; i++) {
        for (int j = 0; j < numReplica; j++) {
          RegionInfo replica = RegionReplicaUtil.getRegionInfoForReplica(hris.get(i), j);
          RegionState state = TEST_UTIL.getHBaseCluster().getMaster().getAssignmentManager()
              .getRegionStates().getRegionState(replica);
          assert (state != null);
        }
      }

      List<Result> metaRows = MetaTableAccessor.fullScanRegions(ADMIN.getConnection());
      int numRows = 0;
      for (Result result : metaRows) {
        RegionLocations locations = MetaTableAccessor.getRegionLocations(result);
        RegionInfo hri = locations.getRegionLocation().getRegionInfo();
        if (!hri.getTable().equals(tableName)) continue;
        numRows += 1;
        HRegionLocation[] servers = locations.getRegionLocations();
        // have two locations for the replicas of a region, and the locations should be different
        assert(servers.length == 2);
        assert(!servers[0].equals(servers[1]));
      }
      assert(numRows == numRegions);

      // The same verification of the meta as above but with the SnapshotOfRegionAssignmentFromMeta
      // class
      validateFromSnapshotFromMeta(TEST_UTIL, tableName, numRegions, numReplica,
        ADMIN.getConnection());

      // Now kill the master, restart it and see if the assignments are kept
      ServerName master = TEST_UTIL.getHBaseClusterInterface().getClusterMetrics().getMasterName();
      TEST_UTIL.getHBaseClusterInterface().stopMaster(master);
      TEST_UTIL.getHBaseClusterInterface().waitForMasterToStop(master, 30000);
      TEST_UTIL.getHBaseClusterInterface().startMaster(master.getHostname(), master.getPort());
      TEST_UTIL.getHBaseClusterInterface().waitForActiveAndReadyMaster();
      for (int i = 0; i < numRegions; i++) {
        for (int j = 0; j < numReplica; j++) {
          RegionInfo replica = RegionReplicaUtil.getRegionInfoForReplica(hris.get(i), j);
          RegionState state = TEST_UTIL.getHBaseCluster().getMaster().getAssignmentManager()
              .getRegionStates().getRegionState(replica);
          assert (state != null);
        }
      }
      validateFromSnapshotFromMeta(TEST_UTIL, tableName, numRegions, numReplica,
        ADMIN.getConnection());
      // Now shut the whole cluster down, and verify the assignments are kept so that the
      // availability constraints are met. MiniHBaseCluster chooses arbitrary ports on each
      // restart. This messes with our being able to test that we retain locality. Therefore,
      // figure current cluster ports and pass them in on next cluster start so new cluster comes
      // up at same coordinates -- and the assignment retention logic has a chance to cut in.
      List<Integer> rsports = new ArrayList<>();
      for (JVMClusterUtil.RegionServerThread rst:
          TEST_UTIL.getHBaseCluster().getLiveRegionServerThreads()) {
        rsports.add(rst.getRegionServer().getRpcServer().getListenerAddress().getPort());
      }
      TEST_UTIL.shutdownMiniHBaseCluster();
      TEST_UTIL.startMiniHBaseCluster(1, numSlaves, rsports);
      TEST_UTIL.waitTableEnabled(tableName);
      validateFromSnapshotFromMeta(TEST_UTIL, tableName, numRegions, numReplica,
        ADMIN.getConnection());

      // Now shut the whole cluster down, and verify regions are assigned even if there is only
      // one server running
      TEST_UTIL.shutdownMiniHBaseCluster();
      TEST_UTIL.startMiniHBaseCluster(1, 1);
      TEST_UTIL.waitTableEnabled(tableName);
      validateSingleRegionServerAssignment(ADMIN.getConnection(), numRegions, numReplica);
      for (int i = 1; i < numSlaves; i++) { //restore the cluster
        TEST_UTIL.getMiniHBaseCluster().startRegionServer();
      }

      // Check on alter table
      ADMIN.disableTable(tableName);
      assert(ADMIN.isTableDisabled(tableName));
      //increase the replica
      desc.setRegionReplication(numReplica + 1);
      ADMIN.modifyTable(tableName, desc);
      ADMIN.enableTable(tableName);
      LOG.info(ADMIN.getTableDescriptor(tableName).toString());
      assert(ADMIN.isTableEnabled(tableName));
      List<RegionInfo> regions = TEST_UTIL.getMiniHBaseCluster().getMaster().
          getAssignmentManager().getRegionStates().getRegionsOfTable(tableName);
      assertTrue("regions.size=" + regions.size() + ", numRegions=" + numRegions +
          ", numReplica=" + numReplica, regions.size() == numRegions * (numReplica + 1));

      //decrease the replica(earlier, table was modified to have a replica count of numReplica + 1)
      ADMIN.disableTable(tableName);
      desc.setRegionReplication(numReplica);
      ADMIN.modifyTable(tableName, desc);
      ADMIN.enableTable(tableName);
      assert(ADMIN.isTableEnabled(tableName));
      regions = TEST_UTIL.getMiniHBaseCluster().getMaster()
          .getAssignmentManager().getRegionStates().getRegionsOfTable(tableName);
      assert(regions.size() == numRegions * numReplica);
      //also make sure the meta table has the replica locations removed
      hris = MetaTableAccessor.getTableRegions(ADMIN.getConnection(), tableName);
      assert(hris.size() == numRegions * numReplica);
      //just check that the number of default replica regions in the meta table are the same
      //as the number of regions the table was created with, and the count of the
      //replicas is numReplica for each region
      Map<RegionInfo, Integer> defaultReplicas = new HashMap<>();
      for (RegionInfo hri : hris) {
        Integer i;
        RegionInfo regionReplica0 = RegionReplicaUtil.getRegionInfoForDefaultReplica(hri);
        defaultReplicas.put(regionReplica0,
            (i = defaultReplicas.get(regionReplica0)) == null ? 1 : i + 1);
      }
      assert(defaultReplicas.size() == numRegions);
      Collection<Integer> counts = new HashSet<>(defaultReplicas.values());
      assert(counts.size() == 1 && counts.contains(numReplica));
    } finally {
      ADMIN.disableTable(tableName);
      ADMIN.deleteTable(tableName);
    }
  }

  @Test @Ignore("Enable when we have support for alter_table- HBASE-10361")
  public void testIncompleteMetaTableReplicaInformation() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    final int numRegions = 3;
    final int numReplica = 2;
    try {
      // Create a table and let the meta table be updated with the location of the
      // region locations.
      HTableDescriptor desc = new HTableDescriptor(tableName);
      desc.setRegionReplication(numReplica);
      desc.addFamily(new HColumnDescriptor("family"));
      ADMIN.createTable(desc, Bytes.toBytes("A"), Bytes.toBytes("Z"), numRegions);
      TEST_UTIL.waitTableEnabled(tableName);
      Set<byte[]> tableRows = new HashSet<>();
      List<RegionInfo> hris = MetaTableAccessor.getTableRegions(ADMIN.getConnection(), tableName);
      for (RegionInfo hri : hris) {
        tableRows.add(hri.getRegionName());
      }
      ADMIN.disableTable(tableName);
      // now delete one replica info from all the rows
      // this is to make the meta appear to be only partially updated
      Table metaTable = ADMIN.getConnection().getTable(TableName.META_TABLE_NAME);
      for (byte[] row : tableRows) {
        Delete deleteOneReplicaLocation = new Delete(row);
        deleteOneReplicaLocation.addColumns(HConstants.CATALOG_FAMILY,
          MetaTableAccessor.getServerColumn(1));
        deleteOneReplicaLocation.addColumns(HConstants.CATALOG_FAMILY,
          MetaTableAccessor.getSeqNumColumn(1));
        deleteOneReplicaLocation.addColumns(HConstants.CATALOG_FAMILY,
          MetaTableAccessor.getStartCodeColumn(1));
        metaTable.delete(deleteOneReplicaLocation);
      }
      metaTable.close();
      // even if the meta table is partly updated, when we re-enable the table, we should
      // get back the desired number of replicas for the regions
      ADMIN.enableTable(tableName);
      assert(ADMIN.isTableEnabled(tableName));
      List<RegionInfo> regions = TEST_UTIL.getMiniHBaseCluster().getMaster()
          .getAssignmentManager().getRegionStates().getRegionsOfTable(tableName);
      assert(regions.size() == numRegions * numReplica);
    } finally {
      ADMIN.disableTable(tableName);
      ADMIN.deleteTable(tableName);
    }
  }

  private String printRegions(List<RegionInfo> regions) {
    StringBuilder strBuf = new StringBuilder();
    for (RegionInfo r : regions) {
      strBuf.append(" ____ " + r.toString());
    }
    return strBuf.toString();
  }

  private void validateNumberOfRowsInMeta(final TableName table, int numRegions,
      Connection connection) throws IOException {
    assert(ADMIN.tableExists(table));
    final AtomicInteger count = new AtomicInteger();
    Visitor visitor = new Visitor() {
      @Override
      public boolean visit(Result r) throws IOException {
        if (MetaTableAccessor.getRegionInfo(r).getTable().equals(table)) count.incrementAndGet();
        return true;
      }
    };
    MetaTableAccessor.fullScanRegions(connection, visitor);
    assert(count.get() == numRegions);
  }

  private void validateFromSnapshotFromMeta(HBaseTestingUtility util, TableName table,
      int numRegions, int numReplica, Connection connection) throws IOException {
    SnapshotOfRegionAssignmentFromMeta snapshot = new SnapshotOfRegionAssignmentFromMeta(
      connection);
    snapshot.initialize();
    Map<RegionInfo, ServerName> regionToServerMap = snapshot.getRegionToRegionServerMap();
    assert(regionToServerMap.size() == numRegions * numReplica + 1); //'1' for the namespace
    Map<ServerName, List<RegionInfo>> serverToRegionMap = snapshot.getRegionServerToRegionMap();
    for (Map.Entry<ServerName, List<RegionInfo>> entry : serverToRegionMap.entrySet()) {
      if (entry.getKey().equals(util.getHBaseCluster().getMaster().getServerName())) {
        continue;
      }
      List<RegionInfo> regions = entry.getValue();
      Set<byte[]> setOfStartKeys = new HashSet<>();
      for (RegionInfo region : regions) {
        byte[] startKey = region.getStartKey();
        if (region.getTable().equals(table)) {
          setOfStartKeys.add(startKey); //ignore other tables
          LOG.info("--STARTKEY {}--", new String(startKey, StandardCharsets.UTF_8));
        }
      }
      // the number of startkeys will be equal to the number of regions hosted in each server
      // (each server will be hosting one replica of a region)
      assertEquals(numRegions, setOfStartKeys.size());
    }
  }

  private void validateSingleRegionServerAssignment(Connection connection, int numRegions,
      int numReplica) throws IOException {
    SnapshotOfRegionAssignmentFromMeta snapshot = new SnapshotOfRegionAssignmentFromMeta(
      connection);
    snapshot.initialize();
    Map<RegionInfo, ServerName>  regionToServerMap = snapshot.getRegionToRegionServerMap();
    assertEquals(regionToServerMap.size(), numRegions * numReplica + 1);
    Map<ServerName, List<RegionInfo>> serverToRegionMap = snapshot.getRegionServerToRegionMap();
    assertEquals("One Region Only", 1, serverToRegionMap.keySet().size());
    for (Map.Entry<ServerName, List<RegionInfo>> entry : serverToRegionMap.entrySet()) {
      if (entry.getKey().equals(TEST_UTIL.getHBaseCluster().getMaster().getServerName())) {
        continue;
      }
      assertEquals(entry.getValue().size(), numRegions * numReplica + 1);
    }
  }
}
