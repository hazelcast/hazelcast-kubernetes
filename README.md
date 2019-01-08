# Hazelcast Discovery Plugin for Kubernetes

This repository contains a plugin which provides the automatic Hazelcast member discovery in the Kubernetes environment.

You can use it in your project deployed on Kubernetes in order to make the embedded Hazelcast members discover each other automatically. This plugin is also included in Hazelcast Docker images, Hazelcast Helm Charts, and Hazelcast OpenShift Docker image.

## Requirements and Recommendations

* Your Java Runtime Environment must support TLS 1.2 (which is the case for most modern JREs).
* Versions compatibility: hazelcast-kubernetes 1.3+ is compatible with hazelcast 3.11+; for older hazelcast versions you need to use hazelcast-kubernetes 1.2.x.
* The recommendation is to use StatefulSet for managing Hazelcast PODs; in case of using Deployment (or ReplicationController), the Hazelcast cluster may start with Split Brain (which will anyway re-form to one consistent cluster in a few minutes).

## Embedded mode

To use Hazelcast embedded in your application, you need to add the plugin dependency into your Maven/Gradle file. Then, when you provide `hazelcast.xml` as presented below or an equivalent Java-based configuration, your Hazelcast instances discover themselves automatically.

#### Maven

```xml
<dependency>
  <groupId>com.hazelcast</groupId>
  <artifactId>hazelcast-kubernetes</artifactId>
  <version>${hazelcast-kubernetes-version}</version>
</dependency>
```

#### Gradle

```groovy
compile group: "com.hazelcast", name: "hazelcast-kubernetes", version: "${hazelcast-kubernetes-version}"
```

## Understanding Discovery Modes

The following table summarizes the differences between the discovery modes: **Kubernetes API** and **DNS Lookup**.

|                | Kubernetes API  | DNS Lookup |
| -------------  | ------------- | ------------- |
| Description    | Uses REST calls to Kubernetes Master to fetch IPs of PODs | Uses DNS to resolve IPs of PODs related to the given service |
| Pros           | Flexible, supports **3 different options**: <br> - Hazelcast cluster per service<br> - Hazelcast cluster per multiple services (distinguished by labels)<br> - Hazelcast cluster per namespace | **No additional configuration** required, resolving DNS does not require granting any permissions  |
| Cons           | Requires setting up **RoleBinding** (to allow access to Kubernetes API)  | - Limited to **headless Cluster IP** service<br> - Limited to **Hazelcast cluster per service**  |

## Configuration

This plugin supports **two different options** of how Hazelcast members discover each others:
* Kubernetes API
* DNS Lookup

### Kubernetes API

**Kubernetes API** mode means that each node makes a REST call to Kubernetes Master in order to discover IPs of PODs (with Hazelcast members).

#### Granting Permissions to use Kubernetes API

Using Kubernetes API requires granting certain permissions. Therefore, you may need to create `rbac.yaml` with the following content.

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: default-cluster
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: view
subjects:
- kind: ServiceAccount
  name: default
  namespace: default
```
  
Then, apply `rbac.yaml`.

```bash
$ kubectl apply -f rbac.yaml
```

*Note*: You can be even more strict with the permissions and create your own Role. For details, please check the implementation of [Hazelcast Helm Chart](https://github.com/helm/charts/tree/master/stable/hazelcast).

#### Creating Service

Hazelcast Kubernetes Discovery requires creating a service to PODs where Hazelcast is running. In case of using Kubernetes API mode, the service can be of any type.

```yaml
kind: Service
metadata:
  name: SERVICE-NAME
spec:
  type: LoadBalancer
  selector:
    app: APP-NAME
  ports:
  - name: hazelcast
    port: 5701
```

#### Hazelcast Configuration

The second step is to configure the discovery plugin inside `hazelcast.xml` or an equivalent Java-based configuration.

```xml
<hazelcast>        
  <network>
    <join>
      <multicast enabled="false"/>
      <kubernetes enabled="true">
        <namespace>MY-KUBERNETES-NAMESPACE</namespace>
        <service-name>MY-SERVICE-NAME</service-name>
      </kubernetes>
    </join>
  </network>
</hazelcast>
```

```java
config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
config.getNetworkConfig().getJoin().getKubernetesConfig().setEnabled(true)
      .setProperty("namespace", "MY-KUBERNETES-NAMESPACE")
      .setProperty("service-name", "MY-SERVICE-NAME");
```

There are 4 properties to configure the plugin, all of them are optional.
 * `namespace`: Kubernetes Namespace where Hazelcast is running; if not specified, the value is taken from the environment variables `KUBERNETES_NAMESPACE` or `OPENSHIFT_BUILD_NAMESPACE`
 * `service-name`: service name used to scan only PODs connected to the given service; if not specified, then all PODs in the namespace are checked
 * `service-label-name`, `service-label-value`: service label and value used to tag services that should form the Hazelcast cluster together
 
You should use either `service-name` or (`service-label-name` and `service-label-value`), specifying all 3 parameters does not make sense.

*Note*: If you don't specify any property at all, then the Hazelcast cluster is formed using all PODs in your current namespace. In other words, you can look at the properties as a grouping feature if you want to have multiple Hazelcast clusters in one namespace.

### DNS Lookup

**DNS Lookup** mode uses a feature of Kubernetes that **headless** (without cluster IP) services are assigned a DNS record which resolves to the set of IPs of related PODs.

#### Creating Headless Service

Headless service is a service of type `ClusterIP` with the `clusterIP` property set to `None`.

```yaml
kind: Service
metadata:
  name: SERVICE-NAME
spec:
  type: ClusterIP
  clusterIP: None
  selector:
    app: APP-NAME
  ports:
  - name: hazelcast
    port: 5701
```

#### Hazelcast Configuration

The Hazelcast configuration to use DNS Lookup looks as follows.

```xml
<hazelcast>
  <network>
    <join>
      <multicast enabled="false"/>
      <kubernetes enabled="true">
        <service-dns>MY-SERVICE-DNS-NAME</service-dns>
      </kubernetes>
    </join>
  </network>
</hazelcast>
```

```java
config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
config.getNetworkConfig().getJoin().getKubernetesConfig().setEnabled(true)
      .setProperty("service-dns", "MY-SERVICE-DNS-NAME");
```

There are 2 properties to configure the plugin:
 * `service-dns` (required): service DNS, usually in the form of `SERVICE-NAME.NAMESPACE.svc.cluster.local`
 * `service-dns-time` (optional): custom time for how long the DNS Lookup is checked

**Note**: In this README, only XML configurations are presented, however you can achieve exactly the same effect using Java-based configurations.

### Zone Aware

When using `ZONE_AWARE` configuration, backups are created in the other availability zone.

**Note**: Your Kubernetes cluster must orchestrate Hazelcast Member PODs equally between the availability zones, otherwise Zone Aware feature may not work correctly.

#### XML Configuration

```xml
<partition-group enabled="true" group-type="ZONE_AWARE" />
```

#### Java-based Configuration

```java
config.getPartitionGroupConfig()
    .setEnabled(true)
    .setGroupType(MemberGroupType.ZONE_AWARE);
```

Note the following aspects of `ZONE_AWARE`:
 * Kubernetes cluster must provide the [well-known Kubernetes annotations](https://kubernetes.io/docs/reference/kubernetes-api/labels-annotations-taints/#failure-domainbetakubernetesiozone)
 * Retrieving Zone Name uses Kubernetes API, so RBAC must be configured as described [here](#granting-permissions-to-use-kubernetes-api)
 * `ZONE_AWARE` feature works correctly when Hazelcast members are distributed equally in all zones, so your Kubernetes cluster must orchestrate PODs equally
 
 Note also that retrieving Zone Name assumes that your container's hostname is the same as POD Name, which is almost always true. If you happen to change your hostname in the container, then please define the following environment variable:
 
 ```yaml
 
env:
  - name: POD_NAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
 ``` 

## Scaling Hazelcast cluster in Kubernetes

Hazelcast cluster is easily scalable within Kubernetes. You can use the standard `kubectl scale` command to change the cluster size.

Note however that, by default, Hazelcast does not shutdown gracefully. It means that if you suddenly scale down by more than your `backup-count` property (1 by default), you may lose the cluster data. To prevent that from happening, set the following properties:
- `terminationGracePeriodSeconds`:  in your StatefulSet (or Deployment) configuration; the value should be high enough to cover the data migration process
- `-Dhazelcast.shutdownhook.policy=GRACEFUL`: in the JVM parameters
- `-Dhazelcast.graceful.shutdown.max.wait`: in the JVM parameters; the value should be high enough to cover the data migration process

The graceful shutdown configuration is already included in [Hazelcast Helm Charts](#helm-chart).

## Plugin Usages

Apart from embedding Hazelcast in your application as described above, there are multiple other scenarios of how to use the Hazelcast Kubernetes plugin.

### Embedded Hazelcast Client

If you have a Hazelcast cluster deployed on Kubernetes, then you can configure Hazelcast Client (deployed on the same Kubernetes cluster). To do it, use exactly the same Maven/Gradle dependencies and the same Discovery Strategy extract in your Hazelcast Client configuration.

Here's an example in case of the **Kubernetes API** mode.

```xml
<hazelcast-client>
  <network>
    <kubernetes enabled="true">
      <namespace>MY-KUBERNETES-NAMESPACE</namespace>
      <service-name>MY-SERVICE-NAME</service-name>
    </kubernetes>
  </network>
</hazelcast-client>
```

```java
clientConfig.getNetworkConfig().getKubernetesConfig().setEnabled(true)
            .setProperty("namespace", "MY-KUBERNETES-NAMESPACE")
            .setProperty("service-name", "MY-SERVICE-NAME");
```

### Docker images

This plugin is included in the official Hazelcast Docker images:

 * [hazelcast/hazelcast](https://hub.docker.com/r/hazelcast/hazelcast/)
 * [hazelcast/hazelcast-enterprise](https://hub.docker.com/r/hazelcast/hazelcast-enterprise)
 
 Please check [Hazelcast Kubernetes Code Samples](https://github.com/hazelcast/hazelcast-code-samples/tree/master/hazelcast-integration/kubernetes) for the their usage.

### Helm Chart

Hazelcast is available in the form of Helm Chart in 3 versions:

 * [stable/hazelcast](https://github.com/helm/charts/tree/master/stable/hazelcast) - Hazelcast IMDG in the official Helm Chart repository
 * [hazelcast/hazelcast](https://github.com/hazelcast/charts/tree/master/stable/hazelcast) - Hazelcast IMDG with Management Center
 * [hazelcast/hazelcast-enterprise](https://github.com/hazelcast/charts/tree/master/stable/hazelcast-enterprise) - Hazelcast Enterprise with Management Center

### Red Hat OpenShift

The plugin is used to provide the OpenShift integration, please check [Hazelcast OpenShift Code Samples](https://github.com/hazelcast/hazelcast-code-samples/tree/master/hazelcast-integration/openshift) for details.
