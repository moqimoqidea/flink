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

package org.apache.flink.connector.print.table;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.functions.util.PrintSinkOutputWriter;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.streaming.api.functions.sink.legacy.PrintSinkFunction;
import org.apache.flink.streaming.api.functions.sink.legacy.RichSinkFunction;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.sink.DynamicTableSink.DataStructureConverter;
import org.apache.flink.table.connector.sink.abilities.SupportsPartitioning;
import org.apache.flink.table.connector.sink.legacy.SinkFunctionProvider;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.DynamicTableSinkFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.types.DataType;

import javax.annotation.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.flink.connector.print.table.PrintConnectorOptions.PRINT_IDENTIFIER;
import static org.apache.flink.connector.print.table.PrintConnectorOptions.STANDARD_ERROR;

/**
 * Print table sink factory writing every row to the standard output or standard error stream. It is
 * designed for: - easy test for streaming job. - very useful in production debugging.
 *
 * <p>Four possible format options: {@code PRINT_IDENTIFIER}:taskId> output <- {@code
 * PRINT_IDENTIFIER} provided, parallelism > 1 {@code PRINT_IDENTIFIER}> output <- {@code
 * PRINT_IDENTIFIER} provided, parallelism == 1 taskId> output <- no {@code PRINT_IDENTIFIER}
 * provided, parallelism > 1 output <- no {@code PRINT_IDENTIFIER} provided, parallelism == 1
 *
 * <p>output string format is "$RowKind[f0, f1, f2, ...]", example is: "+I[1, 1]".
 */
@Internal
public class PrintTableSinkFactory implements DynamicTableSinkFactory {

    public static final String IDENTIFIER = "print";

    @Override
    public String factoryIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        return new HashSet<>();
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        Set<ConfigOption<?>> options = new HashSet<>();
        options.add(PRINT_IDENTIFIER);
        options.add(STANDARD_ERROR);
        options.add(FactoryUtil.SINK_PARALLELISM);
        return options;
    }

    @Override
    public DynamicTableSink createDynamicTableSink(Context context) {
        FactoryUtil.TableFactoryHelper helper = FactoryUtil.createTableFactoryHelper(this, context);
        helper.validate();
        ReadableConfig options = helper.getOptions();
        return new PrintSink(
                context.getCatalogTable().getResolvedSchema().toPhysicalRowDataType(),
                context.getCatalogTable().getPartitionKeys(),
                options.get(PRINT_IDENTIFIER),
                options.get(STANDARD_ERROR),
                options.getOptional(FactoryUtil.SINK_PARALLELISM).orElse(null));
    }

    private static class PrintSink implements DynamicTableSink, SupportsPartitioning {

        private final DataType type;
        private String printIdentifier;
        private final boolean stdErr;
        private final @Nullable Integer parallelism;
        private final List<String> partitionKeys;
        private Map<String, String> staticPartitions = new LinkedHashMap<>();

        private PrintSink(
                DataType type,
                List<String> partitionKeys,
                String printIdentifier,
                boolean stdErr,
                Integer parallelism) {
            this.type = type;
            this.partitionKeys = partitionKeys;
            this.printIdentifier = printIdentifier;
            this.stdErr = stdErr;
            this.parallelism = parallelism;
        }

        @Override
        public ChangelogMode getChangelogMode(ChangelogMode requestedMode) {
            return requestedMode;
        }

        @Override
        public SinkRuntimeProvider getSinkRuntimeProvider(DynamicTableSink.Context context) {
            DataStructureConverter converter = context.createDataStructureConverter(type);
            staticPartitions.forEach(
                    (key, value) -> {
                        printIdentifier = null != printIdentifier ? printIdentifier + ":" : "";
                        printIdentifier += key + "=" + value;
                    });
            return SinkFunctionProvider.of(
                    new RowDataPrintFunction(converter, printIdentifier, stdErr), parallelism);
        }

        @Override
        public DynamicTableSink copy() {
            return new PrintSink(type, partitionKeys, printIdentifier, stdErr, parallelism);
        }

        @Override
        public String asSummaryString() {
            return "Print to " + (stdErr ? "System.err" : "System.out");
        }

        @Override
        public void applyStaticPartition(Map<String, String> partition) {
            // make it a LinkedHashMap to maintain partition column order
            staticPartitions = new LinkedHashMap<>();
            for (String partitionCol : partitionKeys) {
                if (partition.containsKey(partitionCol)) {
                    staticPartitions.put(partitionCol, partition.get(partitionCol));
                }
            }
        }
    }

    /**
     * Implementation of the SinkFunction converting {@link RowData} to string and passing to {@link
     * PrintSinkFunction}.
     */
    private static class RowDataPrintFunction extends RichSinkFunction<RowData> {

        private static final long serialVersionUID = 1L;

        private final DataStructureConverter converter;
        private final PrintSinkOutputWriter<String> writer;

        private RowDataPrintFunction(
                DataStructureConverter converter, String printIdentifier, boolean stdErr) {
            this.converter = converter;
            this.writer = new PrintSinkOutputWriter<>(printIdentifier, stdErr);
        }

        @Override
        public void open(OpenContext openContext) throws Exception {
            super.open(openContext);
            StreamingRuntimeContext context = (StreamingRuntimeContext) getRuntimeContext();
            writer.open(
                    context.getTaskInfo().getIndexOfThisSubtask(),
                    context.getTaskInfo().getNumberOfParallelSubtasks());
        }

        @Override
        public void invoke(RowData value, Context context) {
            Object data = converter.toExternal(value);
            assert data != null;
            writer.write(data.toString());
        }
    }
}
