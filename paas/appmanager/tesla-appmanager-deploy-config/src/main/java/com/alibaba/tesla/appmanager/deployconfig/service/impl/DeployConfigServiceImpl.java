package com.alibaba.tesla.appmanager.deployconfig.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.tesla.appmanager.common.constants.DefaultConstant;
import com.alibaba.tesla.appmanager.common.exception.AppErrorCode;
import com.alibaba.tesla.appmanager.common.exception.AppException;
import com.alibaba.tesla.appmanager.common.util.SchemaUtil;
import com.alibaba.tesla.appmanager.deployconfig.repository.DeployConfigHistoryRepository;
import com.alibaba.tesla.appmanager.deployconfig.repository.DeployConfigRepository;
import com.alibaba.tesla.appmanager.deployconfig.repository.condition.DeployConfigHistoryQueryCondition;
import com.alibaba.tesla.appmanager.deployconfig.repository.condition.DeployConfigQueryCondition;
import com.alibaba.tesla.appmanager.deployconfig.repository.domain.DeployConfigDO;
import com.alibaba.tesla.appmanager.deployconfig.repository.domain.DeployConfigHistoryDO;
import com.alibaba.tesla.appmanager.deployconfig.service.DeployConfigService;
import com.alibaba.tesla.appmanager.domain.container.DeployAppRevisionName;
import com.alibaba.tesla.appmanager.domain.container.DeployConfigEnvId;
import com.alibaba.tesla.appmanager.domain.container.DeployConfigTypeId;
import com.alibaba.tesla.appmanager.domain.req.deployconfig.DeployConfigApplyTemplateReq;
import com.alibaba.tesla.appmanager.domain.req.deployconfig.DeployConfigDeleteReq;
import com.alibaba.tesla.appmanager.domain.req.deployconfig.DeployConfigGenerateReq;
import com.alibaba.tesla.appmanager.domain.req.deployconfig.DeployConfigUpdateReq;
import com.alibaba.tesla.appmanager.domain.res.deployconfig.DeployConfigApplyTemplateRes;
import com.alibaba.tesla.appmanager.domain.res.deployconfig.DeployConfigGenerateRes;
import com.alibaba.tesla.appmanager.domain.schema.DeployAppSchema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ??????????????????
 *
 * @author yaoxing.gyx@alibaba-inc.com
 */
@Service
@Slf4j
public class DeployConfigServiceImpl implements DeployConfigService {

    private final DeployConfigRepository deployConfigRepository;
    private final DeployConfigHistoryRepository deployConfigHistoryRepository;

    public DeployConfigServiceImpl(
            DeployConfigRepository deployConfigRepository,
            DeployConfigHistoryRepository deployConfigHistoryRepository) {
        this.deployConfigRepository = deployConfigRepository;
        this.deployConfigHistoryRepository = deployConfigHistoryRepository;
    }

    /**
     * ?????????????????? (?????? launch yaml ?????????????????????)
     *
     * @param req ????????????
     * @return ????????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeployConfigApplyTemplateRes<DeployConfigDO> applyTemplate(DeployConfigApplyTemplateReq req) {
        String apiVersion = req.getApiVersion();
        String appId = req.getAppId();
        String envId = req.getEnvId();
        String config = req.getConfig();
        boolean enabled = req.isEnabled();
        if (StringUtils.isAnyEmpty(apiVersion, config)) {
            throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                    "invalid apply template request, apiVersion/config are required");
        }

        DeployAppSchema schema = SchemaUtil.toSchema(DeployAppSchema.class, config);
        List<DeployConfigDO> items = new ArrayList<>();

        // ?????? parameterValues ??????
        String parameterTypeId = new DeployConfigTypeId(DeployConfigTypeId.TYPE_PARAMETER_VALUES).toString();
        items.add(applySingleConfig(apiVersion, appId, parameterTypeId, envId,
                SchemaUtil.toYamlStr(schema.getSpec().getParameterValues(), DeployAppSchema.ParameterValue.class),
                enabled, false));
        // ?????? components ??????
        for (DeployAppSchema.SpecComponent component : schema.getSpec().getComponents()) {
            DeployAppRevisionName revision = DeployAppRevisionName.valueOf(component.getRevisionName());
            String componentTypeId = new DeployConfigTypeId(
                    revision.getComponentType(), revision.getComponentName()).toString();
            items.add(applySingleConfig(apiVersion, appId, componentTypeId, envId,
                    SchemaUtil.toYamlMapStr(component), enabled, false));
        }
        return DeployConfigApplyTemplateRes.<DeployConfigDO>builder().items(items).build();
    }

    /**
     * ????????????????????????????????????
     *
     * @param condition ????????????
     * @return ??????????????????
     */
    @Override
    public List<DeployConfigDO> list(DeployConfigQueryCondition condition) {
        return deployConfigRepository.selectByExample(condition);
    }

    /**
     * ??????????????????????????????????????????????????????????????????
     *
     * @param condition ????????????
     * @return ??????????????????
     */
    @Override
    public DeployConfigDO getWithInherit(DeployConfigQueryCondition condition) {
        if (StringUtils.isAnyEmpty(condition.getApiVersion(), condition.getTypeId())) {
            throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                    "invalid getWithInherit parameters, apiVersion/typeId are required");
        }

        List<DeployConfigDO> records = deployConfigRepository.selectByExample(condition);
        if (records.size() == 0) {
            return null;
        } else if (records.size() > 1) {
            throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                    String.format("multiple deploy config records found, abort|condition=%s",
                            JSONObject.toJSONString(condition)));
        }
        DeployConfigDO record = records.get(0);

        // ????????????????????????????????????????????????????????????????????????????????????????????????
        if (record.getInherit() && StringUtils.isEmpty(condition.getAppId())) {
            throw new AppException(AppErrorCode.UNKNOWN_ERROR,
                    String.format("inherit flag and empty appId found at the same time|condition=%s",
                            JSONObject.toJSONString(condition)));
        }

        // ?????????????????????
        if (record.getInherit() == null || !record.getInherit()) {
            return record;
        }

        // ???????????????????????????
        return getWithInherit(DeployConfigQueryCondition.builder()
                .apiVersion(condition.getApiVersion())
                .appId("")
                .typeId(condition.getTypeId())
                .envId(condition.getEnvId())
                .enabled(condition.getEnabled())
                .build());
    }

    /**
     * ???????????? apiVersion + appId + typeId + envId ????????? DeployConfig ??????
     *
     * @param req ????????????
     * @return ??????????????????
     */
    @Override
    public DeployConfigDO update(DeployConfigUpdateReq req) {
        String apiVersion = req.getApiVersion();
        String appId = req.getAppId();
        String envId = req.getEnvId();
        String typeId = req.getTypeId();
        String config = req.getConfig();
        boolean inherit = req.isInherit();
        if (StringUtils.isAnyEmpty(apiVersion, appId, typeId) || (StringUtils.isEmpty(config) && !inherit)) {
            throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                    String.format("invalid deploy config update request|request=%s", JSONObject.toJSONString(req)));
        }

        return applySingleConfig(apiVersion, appId, typeId, envId, config, true, inherit);
    }

    /**
     * ???????????? apiVersion + appId + typeId + envId ????????? DeployConfig ??????
     *
     * @param req ????????????
     */
    @Override
    public void delete(DeployConfigDeleteReq req) {
        String apiVersion = req.getApiVersion();
        String appId = req.getAppId();
        String envId = req.getEnvId();
        String typeId = req.getTypeId();
        if (StringUtils.isAnyEmpty(apiVersion, typeId)) {
            throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                    String.format("invalid deploy config delete request|request=%s", JSONObject.toJSONString(req)));
        }

        deleteSingleConfig(apiVersion, appId, typeId, envId);
    }

    /**
     * ????????????????????????????????????????????? Application Configuration Yaml
     *
     * @param req ????????????
     * @return ?????? Yaml ??????
     */
    @Override
    public DeployConfigGenerateRes generate(DeployConfigGenerateReq req) {
        String apiVersion = req.getApiVersion();
        String appId = req.getAppId();
        if (StringUtils.isAnyEmpty(apiVersion, appId)) {
            throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                    String.format("invalid generate request|request=%s", JSONObject.toJSONString(req)));
        }
        DeployAppSchema schema = generateByConfig(req);

        // ?????? component ????????????????????????????????????
        if (req.isDisableComponentFetching()) {
            schema.getSpec().setComponents(new ArrayList<>());
        }

        return DeployConfigGenerateRes.builder()
                .schema(schema)
                .build();
    }


    /**
     * ?????? deploy config ???????????? application configuration
     *
     * @param req DeployConfigGenerate ??????
     * @return Application Configuration Schema
     */
    private DeployAppSchema generateByConfig(DeployConfigGenerateReq req) {
        String apiVersion = req.getApiVersion();
        String appId = req.getAppId();
        String unitId = req.getUnitId();
        String clusterId = req.getClusterId();
        String namespaceId = req.getNamespaceId();
        String stageId = req.getStageId();

        DeployAppSchema schema = new DeployAppSchema();
        schema.setApiVersion(apiVersion);
        schema.setKind("ApplicationConfiguration");
        schema.setMetadata(DeployAppSchema.MetaData.builder()
                .name(appId)
                .annotations(DeployAppSchema.MetaDataAnnotations.builder()
                        .unitId(unitId)
                        .clusterId(clusterId)
                        .namespaceId(namespaceId)
                        .stageId(stageId)
                        .appId(appId)
                        .appInstanceName(req.getAppInstanceName())
                        .appPackageId(req.getAppPackageId())
                        .build())
                .build());
        schema.setSpec(new DeployAppSchema.Spec());
        schema.getSpec().setParameterValues(new ArrayList<>());
        schema.getSpec().setComponents(new ArrayList<>());

        // ???????????? type ??? schema ???
        List<DeployConfigDO> appRecords = deployConfigRepository.selectByExample(
                DeployConfigQueryCondition.builder()
                        .apiVersion(apiVersion)
                        .appId(appId)
                        .enabled(true)
                        .build());
        List<DeployConfigDO> rootRecords = deployConfigRepository.selectByExample(
                DeployConfigQueryCondition.builder()
                        .apiVersion(apiVersion)
                        .appId("")
                        .enabled(true)
                        .build());
        // ?????????????????????????????? typeIds???????????? componentName ??? (??????????????? typeIds)
        List<String> typeIds = CollectionUtils.isEmpty(req.getTypeIds())
                ? distinctTypeIds(appRecords)
                : req.getTypeIds();
        for (String typeId : typeIds) {
            List<DeployConfigDO> filterAppRecords = appRecords.stream()
                    .filter(item -> item.getTypeId().equals(typeId))
                    .collect(Collectors.toList());
            List<DeployConfigDO> filterRootRecords = rootRecords.stream()
                    .filter(item -> item.getTypeId().equals(typeId))
                    .collect(Collectors.toList());
            DeployConfigDO best = findBestConfigInRecordsBySpecifiedName(
                    filterAppRecords, filterRootRecords, unitId, clusterId, namespaceId, stageId);
            if (best == null) {
                throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                        String.format("cannot find best config in database|appId=%s|typeId=%s|clusterId=%s|" +
                                        "namespaceId=%s|stageId=%s", appId, typeId,
                                clusterId, namespaceId, stageId));
            }
            String config = best.getConfig();
            if (StringUtils.isEmpty(config)) {
                throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                        String.format("invalid inherit config, cannot get config by typeId %s|appRecords=%s|" +
                                        "rootRecords=%s", typeId, JSONArray.toJSONString(filterAppRecords),
                                JSONArray.toJSONString(filterRootRecords)));
            }
            switch (DeployConfigTypeId.valueOf(typeId).getType()) {
                case DeployConfigTypeId.TYPE_PARAMETER_VALUES:
                    schema.getSpec().setParameterValues(
                            SchemaUtil.toSchemaList(DeployAppSchema.ParameterValue.class, config));
                    break;
                case DeployConfigTypeId.TYPE_COMPONENTS:
                    DeployAppSchema.SpecComponent component = enrichComponentScopes(
                            req, SchemaUtil.toSchema(DeployAppSchema.SpecComponent.class, config));
                    schema.getSpec().getComponents().add(component);
                    break;
                default:
                    break;
            }
        }
        return schema;
    }

    /**
     * ???????????? Scope ?????? Cluster/Namespace/Stage ??????
     *
     * @param req    ??????
     * @param schema ApplicationConfiguration ?????? SpecComponent Schema
     * @return DeployAppSchema.SpecComponent
     */
    @Override
    public DeployAppSchema.SpecComponent enrichComponentScopes(
            DeployConfigGenerateReq req, DeployAppSchema.SpecComponent schema) {
        boolean clusterFlag = false;
        boolean namespaceFlag = false;
        boolean stageFlag = false;
        for (DeployAppSchema.SpecComponentScope scope : schema.getScopes()) {
            DeployAppSchema.SpecComponentScopeRef ref = scope.getScopeRef();
            switch (ref.getKind()) {
                case "Cluster":
                    if (StringUtils.isNotEmpty(req.getClusterId())) {
                        ref.setName(req.getClusterId());
                    }
                    clusterFlag = true;
                    break;
                case "Namespace":
                    if (StringUtils.isNotEmpty(req.getNamespaceId())) {
                        ref.setName(req.getNamespaceId());
                    }
                    namespaceFlag = true;
                    break;
                case "Stage":
                    if (StringUtils.isNotEmpty(req.getStageId())) {
                        ref.setName(req.getStageId());
                    }
                    stageFlag = true;
                    break;
                default:
                    break;
            }
        }
        if (!clusterFlag) {
            schema.getScopes().add(DeployAppSchema.SpecComponentScope.builder()
                    .scopeRef(DeployAppSchema.SpecComponentScopeRef.builder()
                            .apiVersion(DefaultConstant.API_VERSION_V1_ALPHA2)
                            .kind("Cluster")
                            .name(req.getClusterId())
                            .spec(new JSONObject())
                            .build())
                    .build());
        }
        if (!namespaceFlag) {
            schema.getScopes().add(DeployAppSchema.SpecComponentScope.builder()
                    .scopeRef(DeployAppSchema.SpecComponentScopeRef.builder()
                            .apiVersion(DefaultConstant.API_VERSION_V1_ALPHA2)
                            .kind("Namespace")
                            .name(req.getNamespaceId())
                            .spec(new JSONObject())
                            .build())
                    .build());
        }
        if (!stageFlag) {
            schema.getScopes().add(DeployAppSchema.SpecComponentScope.builder()
                    .scopeRef(DeployAppSchema.SpecComponentScopeRef.builder()
                            .apiVersion(DefaultConstant.API_VERSION_V1_ALPHA2)
                            .kind("Stage")
                            .name(req.getStageId())
                            .spec(new JSONObject())
                            .build())
                    .build());
        }
        return schema;
    }

    /**
     * ?????? deploy config ????????? envId ????????????????????????????????????
     *
     * @param records deploy config ??????
     * @param envId   ?????? ID
     * @return ???????????????????????????
     */
    private List<DeployConfigDO> filterDeployConfigByEnvId(List<DeployConfigDO> records, String envId) {
        return records.stream()
                .filter(item -> item.getEnvId().contains(envId))
                .sorted((o1, o2) -> {
                    int o1Length = o1.getEnvId().split("::").length;
                    int o2Length = o2.getEnvId().split("::").length;
                    return Integer.compare(o2Length, o1Length);
                })
                .collect(Collectors.toList());
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param records     ??? deploy config ?????? (??? appId???????????????)
     * @param clusterId   ?????? ID
     * @param namespaceId Namespace ID
     * @param stageId     Stage ID
     * @return ??????????????????
     */
    @Override
    public DeployConfigDO findBestConfigInRecordsByGeneralType(
            List<DeployConfigDO> records, String clusterId, String namespaceId, String stageId) {
        List<String> priorities = new ArrayList<>();
        if (StringUtils.isNotEmpty(stageId)) {
            priorities.add(DeployConfigEnvId.stageStr(stageId));
        }
        if (StringUtils.isNotEmpty(namespaceId)) {
            priorities.add(DeployConfigEnvId.namespaceStr(namespaceId));
        }
        if (StringUtils.isNotEmpty(clusterId)) {
            priorities.add(DeployConfigEnvId.clusterStr(clusterId));
        }
        for (String current : priorities) {
            List<DeployConfigDO> filteredRecords = filterDeployConfigByEnvId(records, current);
            if (filteredRecords.size() > 0) {
                return filteredRecords.get(0);
            }
        }
        throw new AppException(AppErrorCode.DEPLOY_ERROR,
                String.format("cannot find best deploy config with given condition|clusterId=%s|namespaceId=%s|" +
                        "stageId=%s", clusterId, namespaceId, stageId));
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param appRecords  ?????????????????? deploy config ??????
     * @param rootRecords ??? deploy config ?????? (??? appId???????????????)
     * @param unitId      ?????? ID
     * @param clusterId   ?????? ID
     * @param namespaceId Namespace ID
     * @param stageId     Stage ID
     * @return ??????????????????
     */
    private DeployConfigDO findBestConfigInRecordsBySpecifiedName(
            List<DeployConfigDO> appRecords, List<DeployConfigDO> rootRecords,
            String unitId, String clusterId, String namespaceId, String stageId) {
        List<String> priorities = new ArrayList<>();
        if (StringUtils.isNotEmpty(stageId)) {
            priorities.add(DeployConfigEnvId.stageStr(stageId));
        }
        if (StringUtils.isNotEmpty(namespaceId)) {
            priorities.add(DeployConfigEnvId.namespaceStr(namespaceId));
        }
        if (StringUtils.isNotEmpty(clusterId)) {
            priorities.add(DeployConfigEnvId.clusterStr(clusterId));
        }
        if (StringUtils.isNotEmpty(unitId)) {
            priorities.add(DeployConfigEnvId.unitStr(unitId));
        }
        for (String current : priorities) {
            List<DeployConfigDO> filteredAppRecords = filterDeployConfigByEnvId(appRecords, current);
            if (filteredAppRecords.size() > 0) {
                DeployConfigDO result = filteredAppRecords.get(0);
                if (result.getInherit() != null && result.getInherit()) {
                    List<DeployConfigDO> filteredRootRecords = filterDeployConfigByEnvId(rootRecords, current);
                    if (filteredRootRecords.size() > 0) {
                        return filteredRootRecords.get(0);
                    }
                }
                return result;
            }
        }

        // ??????
        List<DeployConfigDO> filteredAppRecords = appRecords.stream()
                .filter(item -> StringUtils.isEmpty(item.getEnvId()))
                .collect(Collectors.toList());
        if (filteredAppRecords.size() > 0) {
            DeployConfigDO result = filteredAppRecords.get(0);
            if (result.getInherit() != null && result.getInherit()) {
                for (String current : priorities) {
                    List<DeployConfigDO> filteredRootRecords = filterDeployConfigByEnvId(rootRecords, current);
                    if (filteredRootRecords.size() > 0) {
                        return filteredRootRecords.get(0);
                    }
                }
                List<DeployConfigDO> filterRootRecords = rootRecords.stream()
                        .filter(item -> StringUtils.isEmpty(item.getEnvId()))
                        .collect(Collectors.toList());
                if (filterRootRecords.size() > 0) {
                    return filterRootRecords.get(0);
                }
                throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                        String.format("cannot find inherit record by deploy config app record|unitId=%s|clusterId=%s" +
                                "|namespaceId=%s|stageId=%s|appRecords=%s|rootRecords=%s", unitId, clusterId,
                                namespaceId, stageId, JSONObject.toJSONString(appRecords),
                                JSONObject.toJSONString(rootRecords)));
            }
            return result;
        }
        throw new AppException(AppErrorCode.DEPLOY_ERROR,
                String.format("cannot find best deploy config with given condition|clusterId=%s|namespaceId=%s|" +
                        "stageId=%s", clusterId, namespaceId, stageId));
    }

    /**
     * ?????? Deploy Config ????????? type id ??? distinct ??????
     *
     * @param deployConfigs Deploy Config ????????????
     * @return distinct type ids
     */
    private List<String> distinctTypeIds(List<DeployConfigDO> deployConfigs) {
        Set<String> result = new HashSet<>();
        for (DeployConfigDO config : deployConfigs) {
            result.add(config.getTypeId());
        }
        return new ArrayList<>(result);
    }

    /**
     * ??????????????????????????????
     *
     * @param apiVersion API Version
     * @param appId      ?????? ID
     * @param typeId     ?????? ID
     * @param envId      ?????? ID
     */
    private void deleteSingleConfig(String apiVersion, String appId, String typeId, String envId) {
        if (StringUtils.isEmpty(appId)) {
            appId = "";
        }
        if (StringUtils.isEmpty(envId)) {
            envId = "";
        }

        // ??????????????????
        DeployConfigQueryCondition condition = DeployConfigQueryCondition.builder()
                .appId(appId)
                .typeId(typeId)
                .envId(envId)
                .apiVersion(apiVersion)
                .page(1)
                .pageSize(1)
                .build();
        List<DeployConfigDO> records = deployConfigRepository.selectByExample(condition);
        if (records.size() == 0) {
            log.info("no need to delete single deploy config record|apiVersion={}|appId={}|typeId={}|envId={}",
                    apiVersion, appId, typeId, envId);
            return;
        } else if (records.size() > 1) {
            String errorMessage = String.format("system error, multiple deploy config records found|apiVersion=%s|" +
                    "appId=%s|typeId=%s|envId=%s", apiVersion, appId, typeId, envId);
            throw new AppException(AppErrorCode.INVALID_USER_ARGS, errorMessage);
        }
        Integer revision = records.get(0).getCurrentRevision() + 1;
        String config = records.get(0).getConfig();

        // ????????????
        deployConfigHistoryRepository.insertSelective(DeployConfigHistoryDO.builder()
                .appId(appId)
                .typeId(typeId)
                .envId(envId)
                .apiVersion(apiVersion)
                .revision(revision)
                .config(config)
                .inherit(false)
                .deleted(true)
                .build());
        deployConfigRepository.deleteByExample(condition);
        log.info("deploy config record has deleted|apiVersion={}|appId={}|typeId={}|envId={}", apiVersion, appId,
                typeId, envId);
    }

    /**
     * ?????????????????????????????????????????????
     *
     * @param apiVersion API Version
     * @param appId      ?????? ID
     * @param typeId     ?????? ID
     * @param envId      ?????? ID
     * @param config     ????????????
     * @param enabled    ????????????
     * @param inherit    ????????????
     * @return DeployConfigDO
     */
    private DeployConfigDO applySingleConfig(
            String apiVersion, String appId, String typeId, String envId, String config,
            boolean enabled, boolean inherit) {
        if (StringUtils.isEmpty(envId)) {
            envId = "";
        }

        // ??????????????????????????????????????????
        List<DeployConfigHistoryDO> histories = deployConfigHistoryRepository.selectByExample(
                DeployConfigHistoryQueryCondition.builder()
                        .appId(appId)
                        .typeId(typeId)
                        .envId(envId)
                        .apiVersion(apiVersion)
                        .page(1)
                        .pageSize(1)
                        .build());
        int revision = 0;
        if (CollectionUtils.isNotEmpty(histories)) {
            revision = histories.get(0).getRevision() + 1;
        }

        // ????????????
        deployConfigHistoryRepository.insertSelective(DeployConfigHistoryDO.builder()
                .appId(appId)
                .typeId(typeId)
                .envId(envId)
                .apiVersion(apiVersion)
                .revision(revision)
                .config(config)
                .inherit(inherit)
                .deleted(false)
                .build());
        DeployConfigQueryCondition configCondition = DeployConfigQueryCondition.builder()
                .appId(appId)
                .typeId(typeId)
                .envId(envId)
                .apiVersion(apiVersion)
                .build();
        List<DeployConfigDO> records = deployConfigRepository.selectByExample(configCondition);
        DeployConfigDO result;
        if (records.size() == 0) {
            result = DeployConfigDO.builder()
                    .appId(appId)
                    .typeId(typeId)
                    .envId(envId)
                    .apiVersion(apiVersion)
                    .currentRevision(revision)
                    .config(config)
                    .enabled(enabled)
                    .inherit(inherit)
                    .build();
            try {
                deployConfigRepository.insertSelective(result);
            } catch (Exception e) {
                throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                        String.format("cannot insert deploy config into database|result=%s|exception=%s",
                                JSONObject.toJSONString(result), ExceptionUtils.getStackTrace(e)));
            }
            log.info("deploy config has insert into database|apiVersion={}|appId={}|typeId={}|envId={}|revision={}|" +
                    "enable={}|inherit={}", apiVersion, appId, typeId, envId, revision, enabled, inherit);
        } else {
            DeployConfigDO item = records.get(0);
            item.setCurrentRevision(revision);
            item.setConfig(config);
            item.setEnabled(enabled);
            item.setInherit(inherit);
            try {
                deployConfigRepository.updateByExampleSelective(item, configCondition);
            } catch (Exception e) {
                throw new AppException(AppErrorCode.INVALID_USER_ARGS,
                        String.format("cannot update deploy config in database|item=%s|condition=%s|exception=%s",
                                JSONObject.toJSONString(item), JSONObject.toJSONString(configCondition),
                                ExceptionUtils.getStackTrace(e)));
            }
            log.info("deploy config has updated in database|apiVersion={}|appId={}|typeId={}|envId={}|revision={}|" +
                    "enable={}|inherit={}", apiVersion, appId, typeId, envId, revision, enabled, inherit);
            result = item;
        }
        return result;
    }
}
