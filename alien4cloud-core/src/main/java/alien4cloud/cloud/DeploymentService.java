package alien4cloud.cloud;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.elasticsearch.mapping.QueryHelper;
import org.elasticsearch.mapping.QueryHelper.SearchQueryHelperBuilder;
import org.springframework.stereotype.Component;

import alien4cloud.application.ApplicationEnvironmentService;
import alien4cloud.application.ApplicationVersionService;
import alien4cloud.application.DeploymentSetupService;
import alien4cloud.dao.IGenericSearchDAO;
import alien4cloud.dao.model.GetMultipleDataResult;
import alien4cloud.exception.NotFoundException;
import alien4cloud.model.application.ApplicationEnvironment;
import alien4cloud.model.application.ApplicationVersion;
import alien4cloud.model.application.DeploymentSetup;
import alien4cloud.model.deployment.Deployment;
import alien4cloud.model.deployment.DeploymentSourceType;
import alien4cloud.model.deployment.IDeploymentSource;
import alien4cloud.paas.IPaaSProvider;
import alien4cloud.paas.exception.CloudDisabledException;
import alien4cloud.paas.exception.OperationExecutionException;
import alien4cloud.paas.exception.PaaSAlreadyDeployedException;
import alien4cloud.paas.model.AbstractMonitorEvent;
import alien4cloud.paas.model.DeploymentStatus;
import alien4cloud.paas.model.InstanceInformation;
import alien4cloud.paas.model.OperationExecRequest;
import alien4cloud.paas.model.PaaSDeploymentStatusMonitorEvent;
import alien4cloud.paas.model.PaaSInstanceStateMonitorEvent;
import alien4cloud.paas.model.PaaSInstanceStorageMonitorEvent;
import alien4cloud.paas.model.PaaSMessageMonitorEvent;
import alien4cloud.tosca.container.model.topology.Topology;
import alien4cloud.utils.MapUtil;

import com.google.common.collect.Lists;

/**
 * Manage deployment operations on a cloud.
 */
@Component
@Slf4j
public class DeploymentService {
    @Resource
    private QueryHelper queryHelper;
    @Resource(name = "alien-es-dao")
    private IGenericSearchDAO alienDao;
    @Resource(name = "alien-monitor-es-dao")
    private IGenericSearchDAO alienMonitorDao;
    @Resource
    private CloudService cloudService;
    @Resource
    private ApplicationVersionService applicationVersionService;
    @Resource
    private ApplicationEnvironmentService applicationEnvironmentService;
    @Resource
    private DeploymentSetupService deploymentSetupService;

    /**
     * Get deployments for a given cloud
     *
     * @param cloudId Id of the cloud for which to get deployments (can be null to get deployments for all clouds).
     * @param sourceId Id of the application for which to get deployments (can be null to get deployments for all applications).
     * @param from The start index of the query.
     * @param size The maximum number of elements to return.
     * @return A {@link GetMultipleDataResult} that contains deployments.
     */
    public GetMultipleDataResult<Deployment> getDeployments(String cloudId, String sourceId, int from, int size) {
        return alienDao.search(Deployment.class, null, getDeploymentFilters(cloudId, sourceId, null), from, size);
    }

    /**
     * Get events for a specific deployment from an environment
     * 
     * @param environmentId The environment we want to get events from
     * @param from The initial position of the events to get (based on time desc sorting)
     * @param size The number of events to get.
     * @return A result that contains all events.
     */
    public GetMultipleDataResult<?> getDeploymentEvents(String applicationEnvironmentId, int from, int size) {
        Deployment deployment = getActiveDeploymentFailIfNotExists(applicationEnvironmentId);
        return searchEvents(from, size, deployment);
    }

    /**
     * Get events for a given topology and cloud
     *
     * @param topologyId id of topology for which to get deployment events.
     * @param cloudId id of targeted cloud
     * @param from The initial position of the events to get (based on time desc sorting)
     * @param size The number of events to get.
     * @return A result that contains all events.
     */
    public GetMultipleDataResult<?> getDeploymentEvents(String topologyId, String cloudId, int from, int size) {
        Deployment deployment = getActiveDeploymentFailIfNotExists(topologyId, cloudId);
        return searchEvents(from, size, deployment);
    }

    private GetMultipleDataResult<?> searchEvents(int from, int size, Deployment deployment) {
        String index = alienMonitorDao.getIndexForType(AbstractMonitorEvent.class);
        SearchQueryHelperBuilder searchQueryHelperBuilder = queryHelper
                .buildSearchQuery(index)
                .types(PaaSDeploymentStatusMonitorEvent.class, PaaSInstanceStateMonitorEvent.class, PaaSMessageMonitorEvent.class,
                        PaaSInstanceStorageMonitorEvent.class)
                .filters(MapUtil.newHashMap(new String[] { "deploymentId" }, new String[][] { new String[] { deployment.getId() } }))
                .fieldSort("_timestamp", true);
        return alienMonitorDao.search(searchQueryHelperBuilder, from, size);
    }

    /**
     * Deploy a topology and return the deployment ID
     *
     * @param topology The topology to be deployed.
     * @param deploymentSource Application to be deployed or the Csar that contains test toplogy to be deployed
     * @param deploymentSetup DeploymentSetup used to deploy
     * @return The id of the generated deployment.
     * @throws CloudDisabledException In case the cloud is actually disabled and no deployments can be performed on this cloud.
     */
    public synchronized String deployTopology(Topology topology, IDeploymentSource deploymentSource, DeploymentSetup deploymentSetup)
            throws CloudDisabledException {

        // get the cloud ID from the deploymentmentSetup
        ApplicationEnvironment applicationEnvironment = applicationEnvironmentService.getOrFail(deploymentSetup.getEnvironmentId());
        String cloudId = applicationEnvironment.getCloudId();

        log.info("Deploying topology [{}] on cloud [{}]", topology.getId(), cloudId);
        String topologyId = topology.getId();

        // Check if the topology has already been deployed
        checkSourceNotAlreadyDeployed(cloudId, topologyId);

        // Get underlying paaS provider of the cloud
        IPaaSProvider paaSProvider = cloudService.getPaaSProvider(cloudId);

        // create a deployment object for the given cloud.
        Deployment deployment = new Deployment();
        deployment.setCloudId(cloudId);
        deployment.setId(UUID.randomUUID().toString());
        deployment.setSourceId(deploymentSource.getId());
        deployment.setSourceName(deploymentSource.getName());
        deployment.setSourceType(DeploymentSourceType.fromSourceType(deploymentSource.getClass()));
        deployment.setStartDate(new Date());
        deployment.setDeploymentSetup(deploymentSetup);
        // mendatory for the moment since we could have deployment with no environment (csar test)
        deployment.setTopologyId(topologyId);

        alienDao.save(deployment);
        // save the topology as a deployed topology.
        // change the Id before saving
        topology.setId(deployment.getId());
        alienMonitorDao.save(topology);

        // put back the old Id for deployment
        topology.setId(topologyId);
        String sourceName;
        if (deploymentSource.getName() == null) {
            sourceName = UUID.randomUUID().toString();
        } else {
            sourceName = deploymentSource.getName();
        }
        paaSProvider.deploy(sourceName, deployment.getId(), topology, deploymentSetup);
        log.info("Deployed topology [{}] on cloud [{}], generated deployment with id [{}]", topology.getId(), cloudId, deployment.getId());
        return deployment.getId();
    }

    /**
     * Un-deploy a topology.
     *
     * @param deploymentSetup setup object containing information to deploy
     * @throws CloudDisabledException In case the cloud selected for the topology is disabled.
     */
    public synchronized void undeployTopology(DeploymentSetup deploymentSetup) throws CloudDisabledException {
        String topologyId = deploymentSetupService.getTopologyId(deploymentSetup.getId());
        ApplicationEnvironment environment = deploymentSetupService.getApplicationEnvironment(deploymentSetup.getId());
        log.info("Un-deploying topology [{}] on cloud [{}]", topologyId, environment.getCloudId());
        Deployment activeDeployment = getActiveDeploymentFailIfNotExists(environment.getId());
        this.undeploy(activeDeployment.getId());
    }

    /**
     * Un-deploy a deployment object
     *
     * @param deploymentId deployment id to deploy
     * @throws CloudDisabledException
     */
    public synchronized void undeploy(String deploymentId) throws CloudDisabledException {
        Deployment deployment = getMandatoryDeployment(deploymentId);
        String cloudId = deployment.getCloudId();
        log.info("Un-deploying deployment [{}] on cloud [{}]", deploymentId, cloudId);
        IPaaSProvider paaSProvider = cloudService.getPaaSProvider(cloudId);
        paaSProvider.undeploy(deploymentId);
        deployment.setEndDate(new Date());
        alienDao.save(deployment);
        log.info("Un-deployed deployment [{}] on cloud [{}]", deploymentId, cloudId);
    }

    private Deployment getMandatoryDeployment(String deploymentId) {
        Deployment deployment = alienDao.findById(Deployment.class, deploymentId);
        if (deployment == null) {
            throw new NotFoundException("Deployment <" + deploymentId + "> doesn't exists.");
        }
        return deployment;
    }

    /**
     * Scale up/down a node in a topology.
     *
     * @param applicationEnvironmentId id of the targeted environment
     * @param nodeTemplateId id of the compute node to scale up
     * @param instances the number of instances to be added (if positive) or removed (if negative)
     * @throws CloudDisabledException In case the cloud selected for the topology is disabled.
     */
    public void scale(String applicationEnvironmentId, String nodeTemplateId, int instances) throws CloudDisabledException {
        Deployment deployment = getActiveDeploymentFailIfNotExists(applicationEnvironmentId);
        Topology topology = alienMonitorDao.findById(Topology.class, deployment.getId());
        // change the initial instance to the current instances for the runtime topology.
        topology.getScalingPolicies().get(nodeTemplateId)
                .setInitialInstances(topology.getScalingPolicies().get(nodeTemplateId).getInitialInstances() + instances);
        alienMonitorDao.save(topology);
        // call the paas provider to scale the topology
        IPaaSProvider paaSProvider = cloudService.getPaaSProvider(deployment.getCloudId());
        paaSProvider.scale(deployment.getId(), nodeTemplateId, instances);
    }

    /**
     * Get the current deployment status for a topology.
     *
     * @param deployment deployment for which we want the status
     * @return The status of the topology.
     * @throws CloudDisabledException In case the cloud selected for the topology is disabled.
     */
    public DeploymentStatus getDeploymentStatus(Deployment deployment) throws CloudDisabledException {
        if (deployment == null) {
            return DeploymentStatus.UNDEPLOYED;
        }
        ApplicationEnvironment applicationEnvironment = getEnvironmentByDeployment(deployment.getId());
        String cloudId = applicationEnvironment.getCloudId();
        if (cloudId == null) {
            return DeploymentStatus.UNDEPLOYED;
        }
        IPaaSProvider paaSProvider = cloudService.getPaaSProvider(cloudId);
        DeploymentStatus status = paaSProvider.getStatus(deployment.getId());
        if (status == DeploymentStatus.UNDEPLOYED) {
            deployment.setEndDate(new Date());
            alienDao.save(deployment);
        }
        return status;
    }

    /**
     * Get the detailed status for each instance of each node template.
     *
     * @param topologyId id of the topology
     * @return (map : node template's id => (map : instance's id => instance status))
     * @throws CloudDisabledException In case the cloud selected for the topology is disabled.
     */
    public Map<String, Map<Integer, InstanceInformation>> getInstancesInformation(String topologyId, String cloudId, String environmentId)
            throws CloudDisabledException {
        Deployment deployment = getActiveDeployment(environmentId);
        if (deployment == null) {
            return null;
        }
        IPaaSProvider paaSProvider = cloudService.getPaaSProvider(cloudId);
        Topology runtimeTopology = alienMonitorDao.findById(Topology.class, deployment.getId());
        return paaSProvider.getInstancesInformation(deployment.getId(), runtimeTopology);
    }

    /**
     * Trigger the execution of an operation on a node.
     *
     * @param request the operation's execution description ( see {@link OperationExecRequest})
     * @return a map representing the operation execution response <instanceId,result>
     * @throws CloudDisabledException In case the cloud selected for the topology is disabled.
     * @throws OperationExecutionException runtime exception during an operation
     */
    public Map<String, String> triggerOperationExecution(OperationExecRequest request) throws CloudDisabledException, OperationExecutionException {
        Deployment activeDeployment = this.getActiveDeploymentFailIfNotExists(request.getApplicationEnvironmentId());
        IPaaSProvider paaSProvider = cloudService.getPaaSProvider(activeDeployment.getCloudId());
        return paaSProvider.executeOperation(activeDeployment.getId(), request);
    }

    /**
     * Get the deployed (runtime) topology of an application on a cloud
     *
     * @param applicationEnvironmentId id of the environment
     * @return the Topology requested if found
     */
    public Topology getRuntimeTopology(String applicationEnvironmentId) {
        Deployment deployment = getActiveDeploymentFailIfNotExists(applicationEnvironmentId);
        return alienMonitorDao.findById(Topology.class, deployment.getId());
    }

    /**
     * Get the deployed (runtime) topology of an application on a cloud
     *
     * @param topologyId id of the topology for which to get deployed topology.
     * @param cloudId targeted cloud id
     * @return the Topology requested if found
     */
    public Topology getRuntimeTopology(String topologyId, String cloudId) {
        Deployment deployment = getActiveDeploymentFailIfNotExists(topologyId, cloudId);
        return alienMonitorDao.findById(Topology.class, deployment.getId());
    }

    /**
     * Get an active Deployment given an environment
     * (if not exists throw exception)
     * 
     * @param applicationEnvironmentId id of the environment
     * @return active deployment
     * @throws alien4cloud.exception.NotFoundException if not any deployment exists
     */
    public Deployment getActiveDeploymentFailIfNotExists(String applicationEnvironmentId) {
        Deployment deployment = getActiveDeployment(applicationEnvironmentId);
        if (deployment == null) {
            throw new NotFoundException("Deployment for environment <" + applicationEnvironmentId + "> doesn't exist.");
        }
        return deployment;
    }

    /**
     * Get an active deployment for a given cloud and topology
     * (if not exists throw exception)
     * 
     * @param topologyId id of the topology that has been deployed
     * @param cloudId id of the target cloud
     * @return a deployment
     * @throws alien4cloud.exception.NotFoundException if not any deployment exists
     */
    public Deployment getActiveDeploymentFailIfNotExists(String topologyId, String cloudId) {
        Deployment deployment = getActiveDeployment(topologyId, cloudId);
        if (deployment == null) {
            throw new NotFoundException("Deployment for cloud <" + cloudId + "> and topology <" + topologyId + "> doesn't exist.");
        }
        return deployment;
    }

    /**
     * Get an active deployment for a given environment
     *
     * @param applicationEnvironmentId id of the environment
     * @return active deployment or null if not exist
     */
    public Deployment getActiveDeployment(String applicationEnvironmentId) {
        Deployment deployment = null;
        Map<String, String[]> activeDeploymentFilters = MapUtil.newHashMap(new String[] { "deploymentSetup.environmentId", "endDate" }, new String[][] {
                new String[] { applicationEnvironmentId }, new String[] { null } });
        GetMultipleDataResult<Deployment> dataResult = alienDao.search(Deployment.class, null, activeDeploymentFilters, 1);
        if (dataResult.getData() != null && dataResult.getData().length > 0) {
            deployment = dataResult.getData()[0];
        }
        return deployment;
    }

    /**
     * Get a topology for a given cloud / topology
     * 
     * @param cloudId targeted cloud id
     * @param topologyId id of the topology to deploy
     * @return a deployment
     */
    public Deployment getActiveDeployment(String cloudId, String topologyId) {
        Deployment deployment = null;
        Map<String, String[]> activeDeploymentFilters = MapUtil.newHashMap(new String[] { "cloudId", "topologyId", "endDate" }, new String[][] {
                new String[] { cloudId }, new String[] { topologyId }, new String[] { null } });
        GetMultipleDataResult<Deployment> dataResult = alienDao.search(Deployment.class, null, activeDeploymentFilters, 1);
        if (dataResult.getData() != null && dataResult.getData().length > 0) {
            deployment = dataResult.getData()[0];
        }
        return deployment;
    }

    /**
     * Get a deployment given its id
     *
     * @param id
     * @return
     */
    public Deployment getDeployment(String id) {
        return alienDao.findById(Deployment.class, id);
    }

    /**
     * Get the filters to perform a search query on {@link Deployment} based on the cloudId and sourceId.
     *
     * @param cloudId Id of the cloud on which the application is deployed.
     * @param sourceId Id of the application that is deployed.
     * @param topologyId Id of the topology that is deployed.
     * @return The filters to get deployments.
     */
    private Map<String, String[]> getDeploymentFilters(String cloudId, String sourceId, String topologyId) {
        List<String> filterKeys = Lists.newArrayList();
        List<String[]> filterValues = Lists.newArrayList();
        if (cloudId != null) {
            filterKeys.add("cloudId");
            filterValues.add(new String[] { cloudId });
        }
        if (sourceId != null) {
            filterKeys.add("sourceId");
            filterValues.add(new String[] { sourceId });
        }
        if (topologyId != null) {
            filterKeys.add("topologyId");
            filterValues.add(new String[] { topologyId });
        }
        return MapUtil.newHashMap(filterKeys.toArray(new String[filterKeys.size()]), filterValues.toArray(new String[filterValues.size()][]));
    }

    /**
     * Check if resource has already been deployed
     *
     * @param cloudId id of the cloud on which to check
     * @param topologyId id of the topology
     * @throws alien4cloud.paas.exception.PaaSAlreadyDeployedException if the toplogy has already been deployed
     */
    private void checkSourceNotAlreadyDeployed(String cloudId, String topologyId) {
        // check if the topology is already deployed on this cloud.
        long result = alienDao.count(
                Deployment.class,
                null,
                MapUtil.newHashMap(new String[] { "cloudId", "topologyId", "endDate" }, new String[][] { new String[] { cloudId }, new String[] { topologyId },
                        new String[] { null } }));
        if (result > 0) {
            throw new PaaSAlreadyDeployedException("Topology <" + topologyId + "> is already deployed on this cloud.");
        }
    }

    /**
     * Get all deployments for a given deployment setup id
     *
     * @param deploymentSetupId
     * @return
     */
    public GetMultipleDataResult<Deployment> getDeploymentsByDeploymentSetup(String deploymentSetupId) {
        GetMultipleDataResult<Deployment> deploymentSearch = alienDao.find(Deployment.class,
                MapUtil.newHashMap(new String[] { "deploymentSetup.id" }, new String[][] { new String[] { deploymentSetupId } }), Integer.MAX_VALUE);
        return deploymentSearch;
    }

    /**
     * Get a topology Id for a deployment (through deploymentSetup object)
     * 
     * @param deploymentId
     * @return a topology id
     */
    public String getTopologyIdByDeployment(String deploymentId) {
        Deployment deployment = getMandatoryDeployment(deploymentId);
        if (deployment != null) {
            ApplicationEnvironment applicationEnvironment = applicationEnvironmentService.getOrFail(deployment.getDeploymentSetup().getEnvironmentId());
            ApplicationVersion applicationVersion = applicationVersionService.getOrFail(applicationEnvironment.getCurrentVersionId());
            return applicationVersion.getTopologyId();
        }
        return null;
    }

    /**
     * Get a application environment object linked to the deployment (through deploymentSetup object)
     * 
     * @param deploymentId
     * @return a topology id
     */
    public ApplicationEnvironment getEnvironmentByDeployment(String deploymentId) {
        Deployment deployment = getMandatoryDeployment(deploymentId);
        if (deployment != null) {
            ApplicationEnvironment applicationEnvironment = applicationEnvironmentService.getOrFail(deployment.getDeploymentSetup().getEnvironmentId());
            return applicationEnvironment;
        }
        return null;
    }

}
