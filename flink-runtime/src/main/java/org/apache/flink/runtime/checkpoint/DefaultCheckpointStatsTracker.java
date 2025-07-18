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

package org.apache.flink.runtime.checkpoint;

import org.apache.flink.AttributeBuilder;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.events.EventBuilder;
import org.apache.flink.events.Events;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.metrics.groups.JobManagerJobMetricGroup;
import org.apache.flink.runtime.rest.messages.checkpoints.CheckpointStatistics;
import org.apache.flink.runtime.rest.util.RestMapperUtils;
import org.apache.flink.traces.Span;
import org.apache.flink.traces.SpanBuilder;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.StringWriter;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkState;

public class DefaultCheckpointStatsTracker implements CheckpointStatsTracker {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultCheckpointStatsTracker.class);
    private static final ObjectMapper MAPPER = RestMapperUtils.getStrictObjectMapper();

    /**
     * Lock used to update stats and creating snapshots. Updates always happen from a single Thread
     * at a time and there can be multiple concurrent read accesses to the latest stats snapshot.
     *
     * <p>Currently, writes are executed by whatever Thread executes the coordinator actions (which
     * already happens in locked scope). Reads can come from multiple concurrent Netty event loop
     * Threads of the web runtime monitor.
     */
    private final ReentrantLock statsReadWriteLock = new ReentrantLock();

    /** Checkpoint counts. */
    private final CheckpointStatsCounts counts = new CheckpointStatsCounts();

    /** A summary of the completed checkpoint stats. */
    private final CompletedCheckpointStatsSummary summary = new CompletedCheckpointStatsSummary();

    /** History of checkpoints. */
    private final CheckpointStatsHistory history;

    private final JobManagerJobMetricGroup metricGroup;

    private Optional<JobInitializationMetricsBuilder> jobInitializationMetricsBuilder =
            Optional.empty();
    @Nullable private final CheckpointStatsListener checkpointStatsListener;

    /** Latest created snapshot. */
    private volatile CheckpointStatsSnapshot latestSnapshot;

    /**
     * Flag indicating whether a new snapshot needs to be created. This is true if a new checkpoint
     * was triggered or updated (completed successfully or failed).
     */
    private volatile boolean dirty;

    /** The latest completed checkpoint. Used by the latest completed checkpoint metrics. */
    @Nullable private volatile CompletedCheckpointStats latestCompletedCheckpoint;

    /**
     * Creates a new checkpoint stats tracker.
     *
     * @param numRememberedCheckpoints Maximum number of checkpoints to remember, including in
     *     progress ones.
     * @param metricGroup Metric group for exposed metrics
     */
    public DefaultCheckpointStatsTracker(
            int numRememberedCheckpoints, JobManagerJobMetricGroup metricGroup) {
        this(numRememberedCheckpoints, metricGroup, null);
    }

    /**
     * Creates a new checkpoint stats tracker.
     *
     * @param numRememberedCheckpoints Maximum number of checkpoints to remember, including in
     *     progress ones.
     * @param metricGroup Metric group for exposed metrics.
     * @param checkpointStatsListener Listener for monitoring checkpoint-related events.
     */
    public DefaultCheckpointStatsTracker(
            int numRememberedCheckpoints,
            JobManagerJobMetricGroup metricGroup,
            @Nullable CheckpointStatsListener checkpointStatsListener) {
        checkArgument(numRememberedCheckpoints >= 0, "Negative number of remembered checkpoints");
        this.history = new CheckpointStatsHistory(numRememberedCheckpoints);
        this.metricGroup = metricGroup;
        this.checkpointStatsListener = checkpointStatsListener;

        // Latest snapshot is empty
        latestSnapshot =
                new CheckpointStatsSnapshot(
                        counts.createSnapshot(),
                        summary.createSnapshot(),
                        history.createSnapshot(),
                        null);

        // Register the metrics
        registerMetrics(metricGroup);
    }

    @Override
    public CheckpointStatsSnapshot createSnapshot() {
        CheckpointStatsSnapshot snapshot = latestSnapshot;

        // Only create a new snapshot if dirty and no update in progress,
        // because we don't want to block the coordinator.
        if (dirty && statsReadWriteLock.tryLock()) {
            try {
                // Create a new snapshot
                snapshot =
                        new CheckpointStatsSnapshot(
                                counts.createSnapshot(),
                                summary.createSnapshot(),
                                history.createSnapshot(),
                                jobInitializationMetricsBuilder
                                        .flatMap(
                                                JobInitializationMetricsBuilder
                                                        ::buildRestoredCheckpointStats)
                                        .orElse(null));

                latestSnapshot = snapshot;

                dirty = false;
            } finally {
                statsReadWriteLock.unlock();
            }
        }

        return snapshot;
    }

    // ------------------------------------------------------------------------
    // Callbacks
    // ------------------------------------------------------------------------

    @Override
    public PendingCheckpointStats reportPendingCheckpoint(
            long checkpointId,
            long triggerTimestamp,
            CheckpointProperties props,
            Map<JobVertexID, Integer> vertexToDop) {

        PendingCheckpointStats pending =
                new PendingCheckpointStats(checkpointId, triggerTimestamp, props, vertexToDop);

        statsReadWriteLock.lock();
        try {
            counts.incrementInProgressCheckpoints();
            history.addInProgressCheckpoint(pending);

            dirty = true;
        } finally {
            statsReadWriteLock.unlock();
        }

        return pending;
    }

    @Override
    public void reportRestoredCheckpoint(
            long checkpointID,
            CheckpointProperties properties,
            String externalPath,
            long stateSize) {
        statsReadWriteLock.lock();
        try {
            counts.incrementRestoredCheckpoints();
            checkState(
                    jobInitializationMetricsBuilder.isPresent(),
                    "JobInitializationMetrics should have been set first, before RestoredCheckpointStats");
            jobInitializationMetricsBuilder
                    .get()
                    .setRestoredCheckpointStats(checkpointID, stateSize, properties, externalPath);
            dirty = true;
        } finally {
            statsReadWriteLock.unlock();
        }
    }

    @Override
    public void reportCompletedCheckpoint(CompletedCheckpointStats completed) {
        statsReadWriteLock.lock();
        try {
            latestCompletedCheckpoint = completed;

            counts.incrementCompletedCheckpoints();
            history.replacePendingCheckpointById(completed);

            summary.updateSummary(completed);

            dirty = true;
            logCheckpointStatistics(completed);

            if (checkpointStatsListener != null) {
                checkpointStatsListener.onCompletedCheckpoint();
            }
        } finally {
            statsReadWriteLock.unlock();
        }
    }

    @Override
    public void reportFailedCheckpoint(FailedCheckpointStats failed) {
        statsReadWriteLock.lock();
        try {
            counts.incrementFailedCheckpoints();
            history.replacePendingCheckpointById(failed);

            dirty = true;
            logCheckpointStatistics(failed);

            if (checkpointStatsListener != null) {
                checkpointStatsListener.onFailedCheckpoint();
            }
        } finally {
            statsReadWriteLock.unlock();
        }
    }

    private void logCheckpointStatistics(AbstractCheckpointStats checkpointStats) {
        try {
            EventBuilder eventBuilder =
                    Events.CheckpointEvent.builder(CheckpointStatsTracker.class)
                            .setObservedTsMillis(checkpointStats.getLatestAckTimestamp())
                            .setSeverity("INFO");
            addCommonCheckpointStatsAttributes(eventBuilder, checkpointStats);
            metricGroup.addEvent(eventBuilder);

            SpanBuilder spanBuilder =
                    Span.builder(CheckpointStatsTracker.class, "Checkpoint")
                            .setStartTsMillis(checkpointStats.getTriggerTimestamp())
                            .setEndTsMillis(checkpointStats.getLatestAckTimestamp());
            addCommonCheckpointStatsAttributes(spanBuilder, checkpointStats);
            metricGroup.addSpan(spanBuilder);

            if (LOG.isDebugEnabled()) {
                StringWriter sw = new StringWriter();
                MAPPER.writeValue(
                        sw,
                        CheckpointStatistics.generateCheckpointStatistics(checkpointStats, true));
                String jsonDump = sw.toString();
                LOG.debug(
                        "CheckpointStatistics (for jobID={}, checkpointId={}) dump = {} ",
                        metricGroup.jobId(),
                        checkpointStats.checkpointId,
                        jsonDump);
            }
        } catch (Exception ex) {
            LOG.warn("Fail to log CheckpointStatistics", ex);
        }
    }

    private AttributeBuilder addCommonCheckpointStatsAttributes(
            AttributeBuilder attributeBuilder, AbstractCheckpointStats checkpointStats) {
        attributeBuilder
                .setAttribute("checkpointId", checkpointStats.getCheckpointId())
                .setAttribute("fullSize", checkpointStats.getStateSize())
                .setAttribute("checkpointedSize", checkpointStats.getCheckpointedSize())
                .setAttribute("metadataSize", checkpointStats.getMetadataSize())
                .setAttribute("checkpointStatus", checkpointStats.getStatus().name())
                .setAttribute(
                        "isUnaligned", Boolean.toString(checkpointStats.isUnalignedCheckpoint()))
                .setAttribute(
                        "checkpointType",
                        checkpointStats.getProperties().getCheckpointType().getName());

        return attributeBuilder;
    }

    @Override
    public void reportFailedCheckpointsWithoutInProgress() {
        statsReadWriteLock.lock();
        try {
            counts.incrementFailedCheckpointsWithoutInProgress();

            dirty = true;

            if (checkpointStatsListener != null) {
                checkpointStatsListener.onFailedCheckpoint();
            }
        } finally {
            statsReadWriteLock.unlock();
        }
    }

    @Override
    public PendingCheckpointStats getPendingCheckpointStats(long checkpointId) {
        statsReadWriteLock.lock();
        try {
            AbstractCheckpointStats stats = history.getCheckpointById(checkpointId);
            return stats instanceof PendingCheckpointStats ? (PendingCheckpointStats) stats : null;
        } finally {
            statsReadWriteLock.unlock();
        }
    }

    @Override
    public void reportIncompleteStats(
            long checkpointId, ExecutionAttemptID attemptId, CheckpointMetrics metrics) {
        statsReadWriteLock.lock();
        try {
            AbstractCheckpointStats stats = history.getCheckpointById(checkpointId);
            if (stats instanceof PendingCheckpointStats) {
                ((PendingCheckpointStats) stats)
                        .reportSubtaskStats(
                                attemptId.getJobVertexId(),
                                new SubtaskStateStats(
                                        attemptId.getSubtaskIndex(),
                                        System.currentTimeMillis(),
                                        metrics.getBytesPersistedOfThisCheckpoint(),
                                        metrics.getTotalBytesPersisted(),
                                        metrics.getSyncDurationMillis(),
                                        metrics.getAsyncDurationMillis(),
                                        metrics.getBytesProcessedDuringAlignment(),
                                        metrics.getBytesPersistedDuringAlignment(),
                                        metrics.getAlignmentDurationNanos() / 1_000_000,
                                        metrics.getCheckpointStartDelayNanos() / 1_000_000,
                                        metrics.getUnalignedCheckpoint(),
                                        false));
                dirty = true;
            }
        } finally {
            statsReadWriteLock.unlock();
        }
    }

    @Override
    public void reportInitializationStarted(
            Set<ExecutionAttemptID> toInitialize, long initializationStartTs) {
        jobInitializationMetricsBuilder =
                Optional.of(
                        new JobInitializationMetricsBuilder(toInitialize, initializationStartTs));
    }

    @Override
    public void reportInitializationMetrics(
            ExecutionAttemptID executionAttemptId,
            SubTaskInitializationMetrics initializationMetrics) {
        statsReadWriteLock.lock();
        try {
            if (!jobInitializationMetricsBuilder.isPresent()) {
                LOG.warn(
                        "Attempted to report SubTaskInitializationMetrics [{}] without jobInitializationMetricsBuilder present",
                        initializationMetrics);
                return;
            }
            JobInitializationMetricsBuilder builder = jobInitializationMetricsBuilder.get();
            builder.reportInitializationMetrics(executionAttemptId, initializationMetrics);
            if (builder.isComplete()) {
                traceInitializationMetrics(builder.build());
            }
        } catch (Exception ex) {
            LOG.warn("Failed to log SubTaskInitializationMetrics [{}]", initializationMetrics, ex);
        } finally {
            statsReadWriteLock.unlock();
        }
    }

    private void traceInitializationMetrics(JobInitializationMetrics jobInitializationMetrics) {
        SpanBuilder span =
                Span.builder(CheckpointStatsTracker.class, "JobInitialization")
                        .setStartTsMillis(jobInitializationMetrics.getStartTs())
                        .setEndTsMillis(jobInitializationMetrics.getEndTs())
                        .setAttribute(
                                "initializationStatus",
                                jobInitializationMetrics.getStatus().name());
        for (JobInitializationMetrics.SumMaxDuration duration :
                jobInitializationMetrics.getDurationMetrics().values()) {
            setDurationSpanAttribute(span, duration);
        }
        if (jobInitializationMetrics.getCheckpointId() != JobInitializationMetrics.UNSET) {
            span.setAttribute("checkpointId", jobInitializationMetrics.getCheckpointId());
        }
        if (jobInitializationMetrics.getStateSize() != JobInitializationMetrics.UNSET) {
            span.setAttribute("fullSize", jobInitializationMetrics.getStateSize());
        }
        metricGroup.addSpan(span);
    }

    private void setDurationSpanAttribute(
            SpanBuilder span, JobInitializationMetrics.SumMaxDuration duration) {
        span.setAttribute("max" + duration.getName(), duration.getMax());
        span.setAttribute("sum" + duration.getName(), duration.getSum());
    }

    // ------------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------------

    @VisibleForTesting
    static final String NUMBER_OF_CHECKPOINTS_METRIC = "totalNumberOfCheckpoints";

    @VisibleForTesting
    static final String NUMBER_OF_IN_PROGRESS_CHECKPOINTS_METRIC = "numberOfInProgressCheckpoints";

    @VisibleForTesting
    static final String NUMBER_OF_COMPLETED_CHECKPOINTS_METRIC = "numberOfCompletedCheckpoints";

    @VisibleForTesting
    static final String NUMBER_OF_FAILED_CHECKPOINTS_METRIC = "numberOfFailedCheckpoints";

    @VisibleForTesting
    static final String LATEST_RESTORED_CHECKPOINT_TIMESTAMP_METRIC =
            "lastCheckpointRestoreTimestamp";

    @VisibleForTesting
    static final String LATEST_COMPLETED_CHECKPOINT_SIZE_METRIC = "lastCheckpointSize";

    @VisibleForTesting
    static final String LATEST_COMPLETED_CHECKPOINT_FULL_SIZE_METRIC = "lastCheckpointFullSize";

    @VisibleForTesting
    static final String LATEST_COMPLETED_CHECKPOINT_METADATA_SIZE_METRIC =
            "lastCheckpointMetadataSize";

    @VisibleForTesting
    static final String LATEST_COMPLETED_CHECKPOINT_DURATION_METRIC = "lastCheckpointDuration";

    @VisibleForTesting
    static final String LATEST_COMPLETED_CHECKPOINT_PROCESSED_DATA_METRIC =
            "lastCheckpointProcessedData";

    @VisibleForTesting
    static final String LATEST_COMPLETED_CHECKPOINT_PERSISTED_DATA_METRIC =
            "lastCheckpointPersistedData";

    @VisibleForTesting
    static final String LATEST_COMPLETED_CHECKPOINT_EXTERNAL_PATH_METRIC =
            "lastCheckpointExternalPath";

    @VisibleForTesting
    static final String LATEST_COMPLETED_CHECKPOINT_ID_METRIC = "lastCompletedCheckpointId";

    @VisibleForTesting
    static final String LATEST_CHECKPOINT_COMPLETED_TIMESTAMP = "lastCheckpointCompletedTimestamp";

    /**
     * Register the exposed metrics.
     *
     * @param metricGroup Metric group to use for the metrics.
     */
    private void registerMetrics(MetricGroup metricGroup) {
        metricGroup.gauge(NUMBER_OF_CHECKPOINTS_METRIC, new CheckpointsCounter());
        metricGroup.gauge(
                NUMBER_OF_IN_PROGRESS_CHECKPOINTS_METRIC, new InProgressCheckpointsCounter());
        metricGroup.gauge(
                NUMBER_OF_COMPLETED_CHECKPOINTS_METRIC, new CompletedCheckpointsCounter());
        metricGroup.gauge(NUMBER_OF_FAILED_CHECKPOINTS_METRIC, new FailedCheckpointsCounter());
        metricGroup.gauge(
                LATEST_RESTORED_CHECKPOINT_TIMESTAMP_METRIC,
                new LatestRestoredCheckpointTimestampGauge());
        metricGroup.gauge(
                LATEST_COMPLETED_CHECKPOINT_SIZE_METRIC, new LatestCompletedCheckpointSizeGauge());
        metricGroup.gauge(
                LATEST_COMPLETED_CHECKPOINT_FULL_SIZE_METRIC,
                new LatestCompletedCheckpointFullSizeGauge());
        metricGroup.gauge(
                LATEST_COMPLETED_CHECKPOINT_METADATA_SIZE_METRIC,
                new LatestCompletedCheckpointMetadataSizeGauge());
        metricGroup.gauge(
                LATEST_COMPLETED_CHECKPOINT_DURATION_METRIC,
                new LatestCompletedCheckpointDurationGauge());
        metricGroup.gauge(
                LATEST_COMPLETED_CHECKPOINT_PROCESSED_DATA_METRIC,
                new LatestCompletedCheckpointProcessedDataGauge());
        metricGroup.gauge(
                LATEST_COMPLETED_CHECKPOINT_PERSISTED_DATA_METRIC,
                new LatestCompletedCheckpointPersistedDataGauge());
        metricGroup.gauge(
                LATEST_COMPLETED_CHECKPOINT_EXTERNAL_PATH_METRIC,
                new LatestCompletedCheckpointExternalPathGauge());
        metricGroup.gauge(
                LATEST_COMPLETED_CHECKPOINT_ID_METRIC, new LatestCompletedCheckpointIdGauge());
        metricGroup.gauge(
                LATEST_CHECKPOINT_COMPLETED_TIMESTAMP,
                new LatestCheckpointCompletedTimestampGauge());
    }

    private class CheckpointsCounter implements Gauge<Long> {
        @Override
        public Long getValue() {
            return counts.getTotalNumberOfCheckpoints();
        }
    }

    private class InProgressCheckpointsCounter implements Gauge<Integer> {
        @Override
        public Integer getValue() {
            return counts.getNumberOfInProgressCheckpoints();
        }
    }

    private class CompletedCheckpointsCounter implements Gauge<Long> {
        @Override
        public Long getValue() {
            return counts.getNumberOfCompletedCheckpoints();
        }
    }

    private class FailedCheckpointsCounter implements Gauge<Long> {
        @Override
        public Long getValue() {
            return counts.getNumberOfFailedCheckpoints();
        }
    }

    private class LatestRestoredCheckpointTimestampGauge implements Gauge<Long> {
        @Override
        public Long getValue() {
            return jobInitializationMetricsBuilder
                    .map(JobInitializationMetricsBuilder::getStartTs)
                    .orElse(-1L);
        }
    }

    private class LatestCompletedCheckpointSizeGauge implements Gauge<Long> {
        @Override
        public Long getValue() {
            CompletedCheckpointStats completed = latestCompletedCheckpoint;
            if (completed != null) {
                return completed.getCheckpointedSize();
            } else {
                return -1L;
            }
        }
    }

    private class LatestCompletedCheckpointFullSizeGauge implements Gauge<Long> {
        @Override
        public Long getValue() {
            CompletedCheckpointStats completed = latestCompletedCheckpoint;
            if (completed != null) {
                return completed.getStateSize();
            } else {
                return -1L;
            }
        }
    }

    private class LatestCompletedCheckpointMetadataSizeGauge implements Gauge<Long> {
        @Override
        public Long getValue() {
            CompletedCheckpointStats completed = latestCompletedCheckpoint;
            return completed != null ? completed.getMetadataSize() : -1L;
        }
    }

    private class LatestCompletedCheckpointDurationGauge implements Gauge<Long> {
        @Override
        public Long getValue() {
            CompletedCheckpointStats completed = latestCompletedCheckpoint;
            if (completed != null) {
                return completed.getEndToEndDuration();
            } else {
                return -1L;
            }
        }
    }

    private class LatestCompletedCheckpointProcessedDataGauge implements Gauge<Long> {
        @Override
        public Long getValue() {
            CompletedCheckpointStats completed = latestCompletedCheckpoint;
            if (completed != null) {
                return completed.getProcessedData();
            } else {
                return -1L;
            }
        }
    }

    private class LatestCompletedCheckpointPersistedDataGauge implements Gauge<Long> {
        @Override
        public Long getValue() {
            CompletedCheckpointStats completed = latestCompletedCheckpoint;
            if (completed != null) {
                return completed.getPersistedData();
            } else {
                return -1L;
            }
        }
    }

    private class LatestCompletedCheckpointExternalPathGauge implements Gauge<String> {
        @Override
        public String getValue() {
            CompletedCheckpointStats completed = latestCompletedCheckpoint;
            if (completed != null && completed.getExternalPath() != null) {
                return completed.getExternalPath();
            } else {
                return "n/a";
            }
        }
    }

    private class LatestCompletedCheckpointIdGauge implements Gauge<Long> {
        @Override
        public Long getValue() {
            CompletedCheckpointStats completed = latestCompletedCheckpoint;
            if (completed != null) {
                return completed.getCheckpointId();
            } else {
                return -1L;
            }
        }
    }

    private class LatestCheckpointCompletedTimestampGauge implements Gauge<Long> {
        @Override
        public Long getValue() {
            CompletedCheckpointStats completed = latestCompletedCheckpoint;
            if (completed != null) {
                return completed.getLatestAckTimestamp();
            } else {
                return -1L;
            }
        }
    }
}
