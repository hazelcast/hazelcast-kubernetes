/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.kubernetes;

import com.hazelcast.kubernetes.KubernetesConfig.Mode;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.discovery.AbstractDiscoveryStrategy;
import com.hazelcast.spi.discovery.DiscoveryNode;
import com.hazelcast.spi.partitiongroup.PartitionGroupMetaData;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class HazelcastKubernetesDiscoveryStrategy
        extends AbstractDiscoveryStrategy {
    private final KubernetesClient client;
    private final EndpointResolver endpointResolver;

    private final Map<String, Object> memberMetadata = new HashMap<String, Object>();

    HazelcastKubernetesDiscoveryStrategy(ILogger logger, Map<String, Comparable> properties) {
        super(logger, properties);

        KubernetesConfig config = new KubernetesConfig(properties);
        logger.info(config.toString());

        client = buildKubernetesClient(config);

        if (Mode.DNS_LOOKUP.equals(config.getMode())) {
            endpointResolver = new DnsEndpointResolver(logger, config.getServiceDns(), config.getServicePort(),
                    config.getServiceDnsTimeout());
        } else {
            endpointResolver = new KubernetesApiEndpointResolver(logger, config.getServiceName(), config.getServicePort(),
                    config.getServiceLabelName(), config.getServiceLabelValue(), config.isResolveNotReadyAddresses(), client);
        }

        logger.info("Kubernetes Discovery activated with mode: " + config.getMode().name());
    }

    private static KubernetesClient buildKubernetesClient(KubernetesConfig config) {
        return new KubernetesClient(config.getNamespace(), config.getKubernetesMasterUrl(), config.getKubernetesApiToken(),
                config.getKubernetesCaCertificate(), config.getKubernetesApiRetries());
    }

    public void start() {
        endpointResolver.start();
    }

    @Override
    public Map<String, Object> discoverLocalMetadata() {
        if (memberMetadata.isEmpty()) {
            memberMetadata.put(PartitionGroupMetaData.PARTITION_GROUP_ZONE, discoverZone());
        }
        return memberMetadata;
    }

    private String discoverZone() {
        try {
            String podName = System.getenv("POD_NAME");
            if (podName == null) {
                podName = System.getenv("HOSTNAME");
            }
            if (podName == null) {
                podName = InetAddress.getLocalHost().getHostName();
            }
            String zone = client.zone(podName);
            if (zone != null) {
                return zone;
            }
        } catch (Exception e) {
            // only log the exception and the message, Hazelcast should still start
            getLogger().finest(e);
        }
        getLogger().warning("Cannot fetch the current zone, ZONE_AWARE feature is disabled");
        return "unknown";
    }

    @Override
    public Iterable<DiscoveryNode> discoverNodes() {
        return endpointResolver.resolve();
    }

    public void destroy() {
        endpointResolver.destroy();
    }

    abstract static class EndpointResolver {
        protected final ILogger logger;

        EndpointResolver(ILogger logger) {
            this.logger = logger;
        }

        abstract List<DiscoveryNode> resolve();

        void start() {
        }

        void destroy() {
        }

        protected InetAddress mapAddress(String address) {
            if (address == null) {
                return null;
            }
            try {
                return InetAddress.getByName(address);
            } catch (UnknownHostException e) {
                logger.warning("Address '" + address + "' could not be resolved");
            }
            return null;
        }
    }
}
