/*
 * Copyright (c) 2015, Christoph Engelbert (aka noctarius) and
 * contributors. All rights reserved.
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
package com.noctarius.hazelcast.kubernetes;

import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.properties.PropertyDefinition;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.discovery.AbstractDiscoveryStrategy;
import com.hazelcast.spi.discovery.DiscoveryNode;
import com.hazelcast.util.StringUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static com.noctarius.hazelcast.kubernetes.KubernetesProperties.*;

final class HazelcastKubernetesDiscoveryStrategy
        extends AbstractDiscoveryStrategy {

    private static final String HAZELCAST_SERVICE_PORT = "hazelcast-service-port";

    private final EndpointResolver endpointResolver;

    HazelcastKubernetesDiscoveryStrategy(ILogger logger, Map<String, Comparable> properties) {
        super(logger, properties);

        String serviceDns = getOrNull(properties, KUBERNETES_SYSTEM_PREFIX, SERVICE_DNS);
        String serviceName = getOrNull(properties, KUBERNETES_SYSTEM_PREFIX, SERVICE_NAME);
        String serviceLabel = getOrNull(properties, KUBERNETES_SYSTEM_PREFIX, SERVICE_LABEL_NAME);
        String serviceLabelValue = getOrDefault(properties, KUBERNETES_SYSTEM_PREFIX, SERVICE_LABEL_VALUE, "true");
        String namespace = getOrDefault(properties, KUBERNETES_SYSTEM_PREFIX, NAMESPACE, getNamespaceOrDefault());
        Boolean fallbackToAll = getOrDefault(properties, KUBERNETES_SYSTEM_PREFIX, FALLBACK_TO_ALL, false);

        logger.info("Kubernetes Discovery properties: { " //
                + "service-dns: " + serviceDns + ", " //
                + "service-name: " + serviceName + ", " //
                + "service-label: " + serviceLabel + ", " //
                + "service-label-value: " + serviceLabelValue + ", " //
                + "namespace: " + namespace + ", " //
                + "fallback-to-all-in-ns: " + fallbackToAll //
                + "}");

        EndpointResolver endpointResolver;
        if (serviceDns != null) {
            endpointResolver = new DnsEndpointResolver(logger, serviceDns);
        } else {
            endpointResolver = new ServiceEndpointResolver(logger, serviceName, serviceLabel, serviceLabelValue, namespace, fallbackToAll);
        }
        logger.info("Kubernetes Discovery activated resolver: " + endpointResolver.getClass().getSimpleName());
        this.endpointResolver = endpointResolver;
    }

    private String getNamespaceOrDefault() {
        String namespace = System.getenv("KUBERNETES_NAMESPACE");
        if (namespace == null) {
            namespace = System.getenv("OPENSHIFT_BUILD_NAMESPACE");
            if (namespace == null) {
                namespace = "default";
            }
        }
        return namespace;
    }

    public void start() {
        endpointResolver.start();
    }

    public Iterable<DiscoveryNode> discoverNodes() {
        return endpointResolver.resolve();
    }

    public void destroy() {
        endpointResolver.destroy();
    }

    protected <T extends Comparable> T getOrNull(Map<String, Comparable> properties, String prefix, PropertyDefinition property) {
        return getOrDefault(properties, prefix, property, null);
    }

    protected <T extends Comparable> T getOrDefault(Map<String, Comparable> properties, String prefix,
                                                    PropertyDefinition property, T defaultValue) {
        if (property == null) {
            return defaultValue;
        }

        Comparable value = readProperty(prefix, property);
        if (value == null) {
            value = properties.get(property.key());
        }

        if (value == null) {
            return defaultValue;
        }

        return (T) value;
    }

    private Comparable readProperty(String prefix, PropertyDefinition property) {
        if (prefix != null) {
            String p = getProperty(prefix, property);
            String v = System.getProperty(p);
            if (StringUtil.isNullOrEmpty(v)) {
                v = System.getenv(p);
                if (StringUtil.isNullOrEmpty(v)) {
                    v = System.getenv(cIdentifierLike(p));
                }
            }

            if (!StringUtil.isNullOrEmpty(v)) {
                return property.typeConverter().convert(v);
            }
        }
        return null;
    }

    private String cIdentifierLike(String property) {
        property = property.toUpperCase();
        property = property.replace(".", "_");
        return property.replace("-", "_");
    }

    private String getProperty(String prefix, PropertyDefinition property) {
        StringBuilder sb = new StringBuilder(prefix);
        if (prefix.charAt(prefix.length() - 1) != '.') {
            sb.append('.');
        }
        return sb.append(property.key()).toString();
    }

    static abstract class EndpointResolver {
        private final ILogger logger;

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

        protected int getServicePort(Map<String, Object> properties) {
            int port = NetworkConfig.DEFAULT_PORT;
            if (properties != null) {
                String servicePort = (String) properties.get(HAZELCAST_SERVICE_PORT);
                if (servicePort != null) {
                    port = Integer.parseInt(servicePort);
                }
            }
            return port;
        }
    }
}
