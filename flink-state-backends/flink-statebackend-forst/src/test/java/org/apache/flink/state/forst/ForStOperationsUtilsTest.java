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

package org.apache.flink.state.forst;

import org.apache.flink.util.OperatingSystem;

import org.forstdb.ColumnFamilyOptions;
import org.forstdb.DBOptions;
import org.forstdb.NativeLibraryLoader;
import org.forstdb.RocksDB;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

/** Tests for the {@link ForStOperationUtils}. */
public class ForStOperationsUtilsTest {

    @ClassRule public static final TemporaryFolder TMP_DIR = new TemporaryFolder();

    @BeforeClass
    public static void loadRocksLibrary() throws Exception {
        NativeLibraryLoader.getInstance().loadLibrary(TMP_DIR.newFolder().getAbsolutePath());
    }

    @Test
    public void testPathExceptionOnWindows() throws Exception {
        assumeTrue(OperatingSystem.isWindows());

        final File folder = TMP_DIR.newFolder();
        final File rocksDir =
                new File(folder, getLongString(247 - folder.getAbsolutePath().length()));

        Files.createDirectories(rocksDir.toPath());

        try (DBOptions dbOptions = new DBOptions().setCreateIfMissing(true);
                ColumnFamilyOptions colOptions = new ColumnFamilyOptions()) {

            RocksDB rocks =
                    ForStOperationUtils.openDB(
                            rocksDir.getAbsolutePath(),
                            Collections.emptyList(),
                            Collections.emptyList(),
                            colOptions,
                            dbOptions);
            rocks.close();

            // do not provoke a test failure if this passes, because some setups may actually
            // support long paths, in which case: great!
        } catch (IOException e) {
            assertThat(
                    e.getMessage(),
                    containsString("longer than the directory path length limit for Windows"));
        }
    }

    private static String getLongString(int numChars) {
        final StringBuilder builder = new StringBuilder();
        for (int i = numChars; i > 0; --i) {
            builder.append('a');
        }
        return builder.toString();
    }
}
