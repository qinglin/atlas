/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas.hbase;

import org.apache.atlas.ApplicationProperties;
import org.apache.atlas.AtlasClient;
import org.apache.atlas.hbase.bridge.HBaseAtlasHook;
import org.apache.atlas.hbase.model.HBaseDataTypes;
import org.apache.atlas.typesystem.Referenceable;
import org.apache.atlas.utils.AuthenticationUtil;
import org.apache.atlas.utils.ParamChecker;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Iterator;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;


public class HBaseAtlasHookIT {
    private static final   Logger LOG          = LoggerFactory.getLogger(HBaseAtlasHookIT.class);
    protected static final String DGI_URL      = "http://localhost:31000/";
    protected static final String CLUSTER_NAME = "primary";
    private static HBaseTestingUtility utility;
    private static int                 port;
    private static AtlasClient         atlasClient;


    @BeforeClass
    public void setUp() {
        try {
            createHBaseCluster();
            createAtlasClient();
        } catch (Exception e) {
            LOG.error("Unable to create Hbase Admin for Testing ", e);
        }
    }


    @AfterClass
    public static void cleanup() throws Exception {
        LOG.info(" Stopping mini cluster.. ");
        utility.shutdownMiniCluster();
    }

    @Test
    public void testCreateNamesapce() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", String.valueOf(port));
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        Connection          conn  = ConnectionFactory.createConnection(conf);
        Admin               admin = conn.getAdmin();
        NamespaceDescriptor ns    = NamespaceDescriptor.create("test_namespace").build();
        admin.createNamespace(ns);
        String nameSpace = assertNameSpaceIsRegistered(ns.getName());
        //assert on qualified name
        Referenceable nameSpaceRef           = getAtlasClient().getEntity(nameSpace);
        String        nameSpaceQualifiedName = HBaseAtlasHook.getNameSpaceQualifiedName(CLUSTER_NAME, ns.getName());
        Assert.assertEquals(nameSpaceRef.get(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME), nameSpaceQualifiedName);
    }

    @Test
    public void testCreateTable() throws Exception {
        final Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", "localhost");
        conf.set("hbase.zookeeper.property.clientPort", String.valueOf(port));
        conf.set("zookeeper.znode.parent", "/hbase-unsecure");
        Connection conn      = ConnectionFactory.createConnection(conf);
        Admin      admin     = conn.getAdmin();
        String     namespace = "test_namespace1";
        String     tablename = "test_table";

        // Create a table
        if (!admin.tableExists(TableName.valueOf(namespace, tablename))) {
            NamespaceDescriptor ns = NamespaceDescriptor.create(namespace).build();
            admin.createNamespace(ns);
            HTableDescriptor tableDescriptor = new HTableDescriptor(TableName.valueOf(namespace, tablename));
            tableDescriptor.addFamily(new HColumnDescriptor("colfam1"));
            admin.createTable(tableDescriptor);
        }
        String table = assertTableIsRegistered(namespace, tablename);
        //assert on qualified name
        Referenceable tableRef   = getAtlasClient().getEntity(table);
        String        entityName = HBaseAtlasHook.getTableQualifiedName(CLUSTER_NAME, namespace, tablename);
        Assert.assertEquals(tableRef.get(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME), entityName);
    }

    // Methods for creating HBase

    public static void createAtlasClient() {
        try {
            org.apache.commons.configuration.Configuration configuration = ApplicationProperties.get();
            String[]                                       atlasEndPoint = configuration.getStringArray(HBaseAtlasHook.ATTR_ATLAS_ENDPOINT);
            configuration.setProperty("atlas.cluster.name", CLUSTER_NAME);
            if (atlasEndPoint == null || atlasEndPoint.length == 0) {
                atlasEndPoint = new String[]{DGI_URL};
            }

            Iterator<String> keys = configuration.getKeys();
            while (keys.hasNext()) {
                String key = keys.next();
                LOG.info("{} = {} ", key, configuration.getString(key));
            }

            if (AuthenticationUtil.isKerberosAuthenticationEnabled()) {
                atlasClient = new AtlasClient(configuration, atlasEndPoint);
            } else {
                atlasClient = new AtlasClient(configuration, atlasEndPoint, new String[]{"admin", "admin"});
            }


        } catch (Exception e) {
            LOG.error("Unable to create AtlasClient for Testing ", e);
        }
    }

    private static int getFreePort() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        int          port         = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }

    public static void createHBaseCluster() throws Exception {
        LOG.info("Creating Hbase Admin...");
        port = getFreePort();

        utility = new HBaseTestingUtility();
        utility.getConfiguration().set("test.hbase.zookeeper.property.clientPort", String.valueOf(port));
        utility.getConfiguration().set("hbase.master.port", String.valueOf(getFreePort()));
        utility.getConfiguration().set("hbase.master.info.port", String.valueOf(getFreePort()));
        utility.getConfiguration().set("hbase.regionserver.port", String.valueOf(getFreePort()));
        utility.getConfiguration().set("hbase.regionserver.info.port", String.valueOf(getFreePort()));
        utility.getConfiguration().set("zookeeper.znode.parent", "/hbase-unsecure");
        utility.getConfiguration().set("hbase.table.sanity.checks", "false");
        utility.getConfiguration().set("hbase.coprocessor.master.classes",
                                       "org.apache.atlas.hbase.hook.HBaseAtlasCoprocessor");

        utility.startMiniCluster();
    }


    public AtlasClient getAtlasClient() {
        AtlasClient ret = null;
        if (atlasClient != null) {
            ret = atlasClient;
        }
        return ret;
    }

    protected String assertNameSpaceIsRegistered(String nameSpace) throws Exception {
        return assertNameSpaceIsRegistered(nameSpace, null);
    }

    protected String assertNameSpaceIsRegistered(String nameSpace, HBaseAtlasHookIT.AssertPredicate assertPredicate) throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Searching for nameSpace {}", nameSpace);
        }
        String nameSpaceQualifiedName = HBaseAtlasHook.getNameSpaceQualifiedName(CLUSTER_NAME, nameSpace);
        return assertEntityIsRegistered(HBaseDataTypes.HBASE_NAMESPACE.getName(), AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME,
                                        nameSpaceQualifiedName, assertPredicate);
    }

    protected String assertTableIsRegistered(String nameSpace, String tableName) throws Exception {
        return assertTableIsRegistered(nameSpace, tableName, null);
    }

    protected String assertTableIsRegistered(String nameSpace, String tableName, HBaseAtlasHookIT.AssertPredicate assertPredicate) throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Searching for nameSpace:Table {} {}", nameSpace, tableName);
        }
        String tableQualifiedName = HBaseAtlasHook.getTableQualifiedName(CLUSTER_NAME, nameSpace, tableName);
        return assertEntityIsRegistered(HBaseDataTypes.HBASE_TABLE.getName(), AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, tableQualifiedName,
                                        assertPredicate);
    }

    public interface AssertPredicate {
        void assertOnEntity(Referenceable entity) throws Exception;
    }

    public interface Predicate {
        /**
         * Perform a predicate evaluation.
         *
         * @return the boolean result of the evaluation.
         * @throws Exception thrown if the predicate evaluation could not evaluate.
         */
        void evaluate() throws Exception;
    }


    protected String assertEntityIsRegistered(final String typeName, final String property, final String value,
                                              final HBaseAtlasHookIT.AssertPredicate assertPredicate) throws Exception {
        waitFor(80000, new HBaseAtlasHookIT.Predicate() {
            @Override
            public void evaluate() throws Exception {
                Referenceable entity = atlasClient.getEntity(typeName, property, value);
                assertNotNull(entity);
                if (assertPredicate != null) {
                    assertPredicate.assertOnEntity(entity);
                }
            }
        });
        Referenceable entity = atlasClient.getEntity(typeName, property, value);
        return entity.getId()._getId();
    }

    /**
     * Wait for a condition, expressed via a {@link HBaseAtlasHookIT.Predicate} to become true.
     *
     * @param timeout   maximum time in milliseconds to wait for the predicate to become true.
     * @param predicate predicate waiting on.
     */
    protected void waitFor(int timeout, HBaseAtlasHookIT.Predicate predicate) throws Exception {
        ParamChecker.notNull(predicate, "predicate");
        long mustEnd = System.currentTimeMillis() + timeout;

        while (true) {
            try {
                predicate.evaluate();
                return;
            } catch (Error | Exception e) {
                if (System.currentTimeMillis() >= mustEnd) {
                    fail("Assertions failed. Failing after waiting for timeout " + timeout + " msecs", e);
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Waiting up to {} msec as assertion failed", mustEnd - System.currentTimeMillis(), e);
                }
                Thread.sleep(5000);
            }
        }
    }


}
