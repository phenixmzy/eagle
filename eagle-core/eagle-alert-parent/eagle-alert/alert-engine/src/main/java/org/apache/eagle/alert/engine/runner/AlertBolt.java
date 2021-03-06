/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.eagle.alert.engine.runner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import org.apache.commons.collections.CollectionUtils;
import org.apache.eagle.alert.coordination.model.AlertBoltSpec;
import org.apache.eagle.alert.coordination.model.WorkSlot;
import org.apache.eagle.alert.engine.StreamContextImpl;
import org.apache.eagle.alert.engine.coordinator.IMetadataChangeNotifyService;
import org.apache.eagle.alert.engine.coordinator.MetadataType;
import org.apache.eagle.alert.engine.coordinator.PolicyDefinition;
import org.apache.eagle.alert.engine.coordinator.PublishPartition;
import org.apache.eagle.alert.engine.coordinator.StreamDefinition;
import org.apache.eagle.alert.engine.evaluator.PolicyGroupEvaluator;
import org.apache.eagle.alert.engine.evaluator.impl.AlertBoltOutputCollectorWrapper;
import org.apache.eagle.alert.engine.evaluator.impl.PolicyGroupEvaluatorImpl;
import org.apache.eagle.alert.engine.model.PartitionedEvent;
import org.apache.eagle.alert.engine.router.AlertBoltSpecListener;
import org.apache.eagle.alert.engine.router.impl.StormOutputCollector;
import org.apache.eagle.alert.engine.serialization.SerializationMetadataProvider;
import org.apache.eagle.alert.engine.utils.SingletonExecutor;
import org.apache.eagle.alert.service.IMetadataServiceClient;
import org.apache.eagle.alert.service.MetadataServiceClientImpl;
import org.apache.eagle.alert.utils.AlertConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

import org.apache.storm.metric.api.MultiCountMetric;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;

/**
 * Since 5/1/16.
 * This is container for hosting all policies belonging to the same monitoredStream
 * MonitoredStream refers to tuple of {dataSource, streamId, groupby}
 * The container is also called {@link WorkSlot}
 */
public class AlertBolt extends AbstractStreamBolt implements AlertBoltSpecListener, SerializationMetadataProvider {
    private static final Logger LOG = LoggerFactory.getLogger(AlertBolt.class);
    private static final long serialVersionUID = -4132297691448945672L;
    private PolicyGroupEvaluator policyGroupEvaluator;
    private AlertBoltOutputCollectorWrapper alertOutputCollector;
    private String boltId;
    private boolean logEventEnabled;
    private volatile Object outputLock;
    // mapping from policy name to PolicyDefinition
    private volatile Map<String, PolicyDefinition> cachedPolicies = new HashMap<>(); // for one streamGroup, there are multiple policies

    private volatile Set<PublishPartition> cachedPublishPartitions = new HashSet<>();

    private AlertBoltSpec spec;

    public AlertBolt(String boltId, Config config, IMetadataChangeNotifyService changeNotifyService) {
        super(boltId, changeNotifyService, config);
        this.boltId = boltId;
        this.policyGroupEvaluator = new PolicyGroupEvaluatorImpl(boltId + "-evaluator_stage1"); // use bolt id as evaluatorId.
        // TODO next stage evaluator

        if (config.hasPath("topology.logEventEnabled")) {
            logEventEnabled = config.getBoolean("topology.logEventEnabled");
        }
    }

    @Override
    public void execute(Tuple input) {
        this.streamContext.counter().incr("execute_count");
        try {
            PartitionedEvent pe = deserialize(input.getValueByField(AlertConstants.FIELD_0));
            if (logEventEnabled) {
                LOG.info("Alert bolt {} received event: {}", boltId, pe.getEvent());
            }
            String streamEventVersion = pe.getEvent().getMetaVersion();

            if (streamEventVersion == null) {
                // if stream event version is null, need to initialize it
                pe.getEvent().setMetaVersion(specVersion);
            } else if (streamEventVersion != null && !streamEventVersion.equals(specVersion)) {
                if (specVersion != null && streamEventVersion != null
                    && specVersion.contains("spec_version_") && streamEventVersion.contains("spec_version_")) {
                    // check if specVersion is older than stream_event_version
                    // Long timestamp_of_specVersion = Long.valueOf(specVersion.split("spec_version_")[1]);
                    // Long timestamp_of_streamEventVersion = Long.valueOf(stream_event_version.split("spec_version_")[1]);
                    long timestampOfSpecVersion = Long.valueOf(specVersion.substring(13));
                    long timestampOfStreamEventVersion = Long.valueOf(streamEventVersion.substring(13));
                    specVersionOutofdate = timestampOfSpecVersion < timestampOfStreamEventVersion;
                    if (!specVersionOutofdate) {
                        pe.getEvent().setMetaVersion(specVersion);
                    }
                }

                String message = String.format("Spec Version [%s] of AlertBolt is %s Stream Event Version [%s]!", specVersion, specVersionOutofdate ? "older than" : "newer than", streamEventVersion);
                LOG.warn(message);

                // send out metrics for meta conflict
                this.streamContext.counter().incr("meta_conflict");

                ExecutorService executors = SingletonExecutor.getExecutorService();
                executors.submit(() -> {
                    // if spec version is out-of-date, need to refresh it
                    if (specVersionOutofdate) {
                        try {
                            IMetadataServiceClient client = new MetadataServiceClientImpl(this.getConfig());
                            String topologyId = spec.getTopologyName();
                            AlertBoltSpec latestSpec = client.getVersionedSpec().getAlertSpecs().get(topologyId);
                            if (latestSpec != null) {
                                spec = latestSpec;
                            }
                        } catch (Exception e) {
                            LOG.error(e.toString());
                        }

                    }
                });

            }

            policyGroupEvaluator.nextEvent(pe.withAnchor(input));
            synchronized (outputLock) {
                this.collector.ack(input);
            }
            this.streamContext.counter().incr("ack_count");
        } catch (Exception ex) {
            LOG.error(ex.getMessage(), ex);
            synchronized (outputLock) {
                this.streamContext.counter().incr("fail_count");
                this.collector.fail(input);
            }
        } finally {
            alertOutputCollector.flush();
        }
    }

    @Override
    public void internalPrepare(OutputCollector collector, IMetadataChangeNotifyService metadataChangeNotifyService, Config config, TopologyContext context) {
        // instantiate output lock object
        outputLock = new Object();
        streamContext = new StreamContextImpl(config, context.registerMetric("eagle.evaluator", new MultiCountMetric(), 60), context);
        alertOutputCollector = new AlertBoltOutputCollectorWrapper(new StormOutputCollector(collector), outputLock, streamContext);
        policyGroupEvaluator.init(streamContext, alertOutputCollector);
        metadataChangeNotifyService.registerListener(this);
        metadataChangeNotifyService.init(config, MetadataType.ALERT_BOLT);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields(AlertConstants.FIELD_0, AlertConstants.FIELD_1));
    }

    @Override
    public void cleanup() {
        policyGroupEvaluator.close();
        alertOutputCollector.flush();
        alertOutputCollector.close();
        super.cleanup();
    }

    @SuppressWarnings("unchecked")
    @Override
    public synchronized void onAlertBoltSpecChange(AlertBoltSpec spec, Map<String, StreamDefinition> sds) {
        List<PolicyDefinition> newPolicies = spec.getBoltPoliciesMap().get(boltId);
        if (newPolicies == null) {
            LOG.info("no new policy with AlertBoltSpec {} for this bolt {}", spec, boltId);
            return;
        }

        Map<String, PolicyDefinition> newPoliciesMap = new HashMap<>();
        newPolicies.forEach(p -> newPoliciesMap.put(p.getName(), p));
        MapComparator<String, PolicyDefinition> comparator = new MapComparator<>(newPoliciesMap, cachedPolicies);
        comparator.compare();

        MapComparator<String, StreamDefinition> streamComparator = new MapComparator<>(sds, sdf);
        streamComparator.compare();

        List<StreamDefinition> addOrUpdatedStreams = streamComparator.getAdded();
        addOrUpdatedStreams.addAll(streamComparator.getModified());
        List<PolicyDefinition> cachedPoliciesTemp = new ArrayList<>(cachedPolicies.values());
        addOrUpdatedStreams.forEach(s -> {
            cachedPoliciesTemp.stream().filter(p -> p.getInputStreams().contains(s.getStreamId())
                || p.getOutputStreams().contains(s.getStreamId())).forEach(p -> {
                    if (comparator.getModified().stream().filter(x -> x.getName().equals(p.getName())).count() <= 0
                        && comparator.getAdded().stream().filter(x -> x.getName().equals(p.getName())).count() <= 0) {
                        comparator.getModified().add(p);
                    }
                });
            ;
        });

        policyGroupEvaluator.onPolicyChange(spec.getVersion(), comparator.getAdded(), comparator.getRemoved(), comparator.getModified(), sds);

        // update alert output collector
        Set<PublishPartition> newPublishPartitions = new HashSet<>();
        spec.getPublishPartitions().forEach(p -> {
            if (newPolicies.stream().filter(o -> o.getName().equals(p.getPolicyId())).count() > 0) {
                newPublishPartitions.add(p);
            }
        });

        Collection<PublishPartition> addedPublishPartitions = CollectionUtils.subtract(newPublishPartitions, cachedPublishPartitions);
        Collection<PublishPartition> removedPublishPartitions = CollectionUtils.subtract(cachedPublishPartitions, newPublishPartitions);
        Collection<PublishPartition> modifiedPublishPartitions = CollectionUtils.intersection(newPublishPartitions, cachedPublishPartitions);

        LOG.debug("added PublishPartition " + addedPublishPartitions);
        LOG.debug("removed PublishPartition " + removedPublishPartitions);
        LOG.debug("modified PublishPartition " + modifiedPublishPartitions);

        alertOutputCollector.onAlertBoltSpecChange(addedPublishPartitions, removedPublishPartitions, modifiedPublishPartitions);

        // switch
        cachedPolicies = newPoliciesMap;
        cachedPublishPartitions = newPublishPartitions;
        sdf = sds;
        specVersion = spec.getVersion();
        this.spec = spec;
    }

}