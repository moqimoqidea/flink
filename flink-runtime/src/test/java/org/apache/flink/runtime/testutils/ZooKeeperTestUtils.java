/*
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

package org.apache.flink.runtime.testutils;

import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.configuration.RpcOptions;
import org.apache.flink.configuration.StateBackendOptions;
import org.apache.flink.runtime.jobmanager.HighAvailabilityMode;

import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.apache.flink.util.Preconditions.checkNotNull;

/** ZooKeeper test utilities. */
public class ZooKeeperTestUtils {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperTestUtils.class);

    /**
     * Creates a new {@link TestingServer}, setting additional configuration properties for
     * stability purposes.
     */
    public static TestingServer createAndStartZookeeperTestingServer() throws Exception {
        return new TestingServer(getZookeeperInstanceSpecWithIncreasedSessionTimeout(), true);
    }

    private static InstanceSpec getZookeeperInstanceSpecWithIncreasedSessionTimeout() {
        // this gives us the default settings
        final InstanceSpec instanceSpec = InstanceSpec.newInstanceSpec();

        final Map<String, Object> properties = new HashMap<>();
        properties.put("maxSessionTimeout", "60000");

        final boolean deleteDataDirectoryOnClose = true;

        return new InstanceSpec(
                instanceSpec.getDataDirectory(),
                instanceSpec.getPort(),
                instanceSpec.getElectionPort(),
                instanceSpec.getQuorumPort(),
                deleteDataDirectoryOnClose,
                instanceSpec.getServerId(),
                instanceSpec.getTickTime(),
                instanceSpec.getMaxClientCnxns(),
                properties,
                instanceSpec.getHostname());
    }

    /**
     * Creates a configuration to operate in {@link HighAvailabilityMode#ZOOKEEPER}.
     *
     * @param zooKeeperQuorum ZooKeeper quorum to connect to
     * @param fsStateHandlePath Base path for file system state backend (for checkpoints and
     *     recovery)
     * @return A new configuration to operate in {@link HighAvailabilityMode#ZOOKEEPER}.
     */
    public static Configuration createZooKeeperHAConfig(
            String zooKeeperQuorum, String fsStateHandlePath) {

        return configureZooKeeperHA(new Configuration(), zooKeeperQuorum, fsStateHandlePath);
    }

    /**
     * Sets all necessary configuration keys to operate in {@link HighAvailabilityMode#ZOOKEEPER}.
     *
     * @param config Configuration to use
     * @param zooKeeperQuorum ZooKeeper quorum to connect to
     * @param fsStateHandlePath Base path for file system state backend (for checkpoints and
     *     recovery)
     * @return The modified configuration to operate in {@link HighAvailabilityMode#ZOOKEEPER}.
     */
    public static Configuration configureZooKeeperHA(
            Configuration config, String zooKeeperQuorum, String fsStateHandlePath) {

        checkNotNull(config, "Configuration");
        checkNotNull(zooKeeperQuorum, "ZooKeeper quorum");
        checkNotNull(fsStateHandlePath, "File state handle backend path");

        // ZooKeeper recovery mode
        config.set(HighAvailabilityOptions.HA_MODE, "ZOOKEEPER");
        config.set(HighAvailabilityOptions.HA_ZOOKEEPER_QUORUM, zooKeeperQuorum);

        int connTimeout = 5000;
        if (runsOnCIInfrastructure()) {
            // The regular timeout is to aggressive for Travis and connections are often lost.
            LOG.info(
                    "Detected CI environment: Configuring connection and session timeout of 30 seconds");
            connTimeout = 30000;
        }

        config.set(
                HighAvailabilityOptions.ZOOKEEPER_CONNECTION_TIMEOUT,
                Duration.ofMillis(connTimeout));
        config.set(
                HighAvailabilityOptions.ZOOKEEPER_SESSION_TIMEOUT, Duration.ofMillis(connTimeout));

        // File system state backend
        config.set(StateBackendOptions.STATE_BACKEND, "hashmap");
        config.set(CheckpointingOptions.CHECKPOINTS_DIRECTORY, fsStateHandlePath + "/checkpoints");
        config.set(HighAvailabilityOptions.HA_STORAGE_PATH, fsStateHandlePath + "/recovery");

        config.set(RpcOptions.ASK_TIMEOUT_DURATION, Duration.ofSeconds(100));

        return config;
    }

    /**
     * @return true, if a CI environment is detected.
     */
    public static boolean runsOnCIInfrastructure() {
        return System.getenv().containsKey("CI") || System.getenv().containsKey("TF_BUILD");
    }
}
