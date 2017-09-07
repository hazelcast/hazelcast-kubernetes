/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.spi.discovery.DiscoveryNode;
import com.hazelcast.spi.discovery.SimpleDiscoveryNode;
import com.hazelcast.util.StringUtil;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointsList;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

class ServiceEndpointResolver extends HazelcastKubernetesDiscoveryStrategy.EndpointResolver {

    private final String serviceName;
    private final String serviceLabel;
    private final String serviceLabelValue;
    private final String namespace;

    private final KubernetesClient client;

    ServiceEndpointResolver(ILogger logger, String serviceName, String serviceLabel, String serviceLabelValue,
            String namespace, String kubernetesMaster, String apiToken) {

        super(logger);

        this.serviceName = serviceName;
        this.namespace = namespace;
        this.serviceLabel = serviceLabel;
        this.serviceLabelValue = serviceLabelValue;
        this.client = buildKubernetesClient(apiToken, kubernetesMaster);
    }

    private KubernetesClient buildKubernetesClient(String token, String kubernetesMaster) {
        if (StringUtil.isNullOrEmpty(token)) {
            token = getAccountToken();
        }
        logger.info("Kubernetes Discovery: Bearer Token { " + token + " }");
        Config config = new ConfigBuilder().withOauthToken(token).withMasterUrl(kubernetesMaster).build();
        return new DefaultKubernetesClient(config);
    }

    @Override
    List<DiscoveryNode> resolve() {
        List<DiscoveryNode> result = Collections.emptyList();
        if (serviceName != null && !serviceName.isEmpty()) {
            Endpoints endpoints = call(new RetryableOperation<Endpoints>() {
                @Override
                public Endpoints exec() {
                    return client.endpoints().inNamespace(namespace).withName(serviceName).get();
                }
            });
            result = getSimpleDiscoveryNodes(endpoints);
        }

        if (result.isEmpty() && serviceLabel != null && !serviceLabel.isEmpty()) {
            EndpointsList endpoints = call(new RetryableOperation<EndpointsList>() {
                @Override
                public EndpointsList exec() {
                    return client.endpoints().inNamespace(namespace).withLabel(serviceLabel, serviceLabelValue).list();
                }
            });
            result = getDiscoveryNodes(endpoints);
        }

        return result.isEmpty() ? getNodesByNamespace() : result;
    }

    private List<DiscoveryNode> getNodesByNamespace() {
        final EndpointsList endpointsInNamespace = call(new RetryableOperation<EndpointsList>() {
            @Override
            public EndpointsList exec() {
                return client.endpoints().inNamespace(namespace).list();
            }
        });
        if (endpointsInNamespace == null) {
            return Collections.emptyList();
        }
        return getDiscoveryNodes(endpointsInNamespace);
    }

    private List<DiscoveryNode> getDiscoveryNodes(EndpointsList endpointsInNamespace) {
        if (endpointsInNamespace == null) {
            return Collections.emptyList();
        }
        List<DiscoveryNode> discoveredNodes = new ArrayList<DiscoveryNode>();
        for (Endpoints endpoints : endpointsInNamespace.getItems()) {
            discoveredNodes.addAll(getSimpleDiscoveryNodes(endpoints));
        }
        return discoveredNodes;
    }

    private List<DiscoveryNode> getSimpleDiscoveryNodes(Endpoints endpoints) {
        if (endpoints == null) {
            return Collections.emptyList();
        }
        List<DiscoveryNode> discoveredNodes = new ArrayList<DiscoveryNode>();
        for (EndpointSubset endpointSubset : endpoints.getSubsets()) {
            if (endpointSubset.getAddresses() != null) {
                for (EndpointAddress endpointAddress : endpointSubset.getAddresses()) {
                    addAddress(discoveredNodes, endpointAddress);
                }
            }
            if (endpointSubset.getNotReadyAddresses() != null) {
                for (EndpointAddress endpointAddress : endpointSubset.getNotReadyAddresses()) {
                    addAddress(discoveredNodes, endpointAddress);
                }
            }
        }
        return discoveredNodes;
    }

    private void addAddress(List<DiscoveryNode> discoveredNodes, EndpointAddress endpointAddress) {
        Map<String, Object> properties = endpointAddress.getAdditionalProperties();
        String ip = endpointAddress.getIp();
        InetAddress inetAddress = mapAddress(ip);
        int port = getServicePort(properties);
        Address address = new Address(inetAddress, port);
        discoveredNodes.add(new SimpleDiscoveryNode(address, properties));
    }

    @Override
    void destroy() {
        client.close();
    }

    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    private String getAccountToken() {
        String serviceTokenCandidate = null;
        try {
            serviceTokenCandidate = new String(Files.readAllBytes(
                    new File(Config.KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH).toPath()), Charset.defaultCharset());
        } catch (IOException e) {
            throw new RuntimeException("Could not get token file", e);
        }

        return serviceTokenCandidate;
    }

    /**
     * Performs a Kubernetes API call retrying operation in case an error is thrown.
     */
    private <T extends Object> T call(RetryableOperation<T> template) {
        return template.wrapExec();
    }

    private abstract class RetryableOperation<T> {

        private static final int MAX_NUMBER_OF_RETRIES = 5;

        public abstract T exec();

        T wrapExec() {
            T result = null;
            boolean retry = true;
            int triesCount = 0;
            do {
                try {
                    triesCount++;
                    result = exec();
                    retry = false;
                } catch (KubernetesClientException ke) {
                    logger.warning(String.format("Could not perform operation in cluster. Retry [%s]", triesCount), ke);
                    if (triesCount == MAX_NUMBER_OF_RETRIES) {
                        throw ke;
                    }
                }
                try {
                    final long oneSecond = 1000;
                    Thread.sleep(triesCount * oneSecond);
                } catch (InterruptedException e) {
                    logger.warning("Error waiting up sleep period", e);
                }
            } while (retry && triesCount < MAX_NUMBER_OF_RETRIES);
            return result;
        }
    }
}
