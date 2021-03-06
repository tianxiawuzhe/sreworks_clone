package com.alibaba.tesla.appmanager.server.controller;

import com.alibaba.tesla.appmanager.api.provider.AppComponentProvider;
import com.alibaba.tesla.appmanager.api.provider.AppPackageTaskProvider;
import com.alibaba.tesla.appmanager.api.provider.ComponentPackageProvider;
import com.alibaba.tesla.appmanager.auth.controller.AppManagerBaseController;
import com.alibaba.tesla.appmanager.common.constants.DefaultConstant;
import com.alibaba.tesla.appmanager.common.enums.ComponentTypeEnum;
import com.alibaba.tesla.appmanager.common.exception.AppErrorCode;
import com.alibaba.tesla.appmanager.common.exception.AppException;
import com.alibaba.tesla.appmanager.common.pagination.Pagination;
import com.alibaba.tesla.appmanager.domain.dto.AppComponentDTO;
import com.alibaba.tesla.appmanager.domain.dto.AppPackageTaskDTO;
import com.alibaba.tesla.appmanager.domain.dto.ComponentPackageVersionItemDTO;
import com.alibaba.tesla.appmanager.domain.req.appcomponent.AppComponentQueryReq;
import com.alibaba.tesla.appmanager.domain.req.apppackage.AppPackageTaskCreateReq;
import com.alibaba.tesla.appmanager.domain.req.apppackage.AppPackageTaskNextLatestVersionReq;
import com.alibaba.tesla.appmanager.domain.req.apppackage.AppPackageTaskQueryReq;
import com.alibaba.tesla.appmanager.domain.req.apppackage.ComponentBinder;
import com.alibaba.tesla.appmanager.domain.req.componentpackage.ComponentPackageLatestVersionListReq;
import com.alibaba.tesla.appmanager.domain.res.apppackage.AppPackageTaskCreateRes;
import com.alibaba.tesla.common.base.TeslaBaseResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

import static com.alibaba.tesla.appmanager.common.constants.DefaultConstant.INTERNAL_ADDON_APP_META;
import static com.alibaba.tesla.appmanager.common.constants.DefaultConstant.INTERNAL_ADDON_DEVELOPMENT_META;

/**
 * App Package ???????????? Controller
 *
 * @author yaoxing.gyx@alibaba-inc.com
 */
@Slf4j
@RequestMapping
@RestController
public class AppPackageTaskController extends AppManagerBaseController {

    @Autowired
    private AppPackageTaskProvider appPackageTaskProvider;

    @Autowired
    private AppComponentProvider appComponentProvider;

    @Autowired
    private ComponentPackageProvider componentPackageProvider;

    /**
     * @api {post} /apps/:appId/app-package-tasks ???????????????????????????
     * @apiName PostApplicationPackageTask
     * @apiGroup ??????????????? API
     * @apiParam (Path Parameters) {String} appId ?????? ID
     * @apiParam (JSON Body) {String} version ?????????
     * @apiParam (JSON Body) {String[]} tags ????????????
     * @apiParam (JSON Body) {Object[]} components ????????????
     */
    @PostMapping("/apps/{appId}/app-package-tasks")
    @ResponseBody
    public TeslaBaseResult create(
            @PathVariable String appId, @RequestBody AppPackageTaskCreateReq request,
            OAuth2Authentication auth) {
        if (CollectionUtils.isEmpty(request.getTags())) {
            return buildClientErrorResult("tags is required");
        }

        try {
            request.setAppId(appId);
            AppPackageTaskCreateRes response = appPackageTaskProvider.create(request, getOperator(auth));
            return buildSucceedResult(response);
        } catch (AppException e) {
            return buildResult(e.getErrorCode().getCode(), e.getErrorMessage(), e.getErrorData());
        }
    }

    /**
     * @api {get} /apps/:appId/app-package-tasks ???????????????????????????
     * @apiName GetApplicationPackageTaskList
     * @apiGroup ??????????????? API
     * @apiParam (Path Parameters) {String} appId ?????? ID
     */
    @GetMapping("/apps/{appId}/app-package-tasks")
    @ResponseBody
    public TeslaBaseResult list(@PathVariable String appId, OAuth2Authentication auth) {
        AppPackageTaskQueryReq request = AppPackageTaskQueryReq.builder()
                .appId(appId)
                .build();
        return buildSucceedResult(appPackageTaskProvider.list(request, getOperator(auth), true));
    }

    /**
     * @api {get} /apps/:appId/app-package-tasks ???????????????????????????
     * @apiName GetApplicationPackageTaskList
     * @apiGroup ??????????????? API
     * @apiParam (Path Parameters) {String} appId ?????? ID
     */
    @GetMapping("/app-package-tasks")
    @ResponseBody
    public TeslaBaseResult listCompatible(@ModelAttribute AppPackageTaskQueryReq request, OAuth2Authentication auth) {
        return buildSucceedResult(appPackageTaskProvider.list(
                AppPackageTaskQueryReq.builder().appId(request.getAppId()).build(),
                getOperator(auth), true));
    }

    /**
     * @api {get} /apps/:appId/app-package-tasks/:taskId ?????????????????????????????????
     * @apiName GetApplicationPackageTask
     * @apiGroup ??????????????? API
     * @apiParam (Path Parameters) {String} appId ?????? ID
     * @apiParam (Path Parameters) {Number} taskId ?????? ID
     */
    @GetMapping("/apps/{appId}/app-package-tasks/{taskId}")
    @ResponseBody
    public TeslaBaseResult get(
            @PathVariable String appId, @PathVariable Long taskId,
            OAuth2Authentication auth) {
        AppPackageTaskQueryReq request = AppPackageTaskQueryReq.builder()
                .appId(appId)
                .appPackageTaskId(taskId)
                .withBlobs(true)
                .build();
        Pagination<AppPackageTaskDTO> tasks = appPackageTaskProvider.list(request, getOperator(auth), true);
        if (!tasks.isEmpty()) {
            return buildSucceedResult(tasks.getItems().get(0));
        }
        return buildClientErrorResult(AppErrorCode.INVALID_USER_ARGS.getDescription());
    }

    @PostMapping("/apps/{appId}/app-package-tasks/quick-create")
    @ResponseBody
    public TeslaBaseResult quickCreate(
            @PathVariable String appId, @RequestBody AppPackageTaskCreateReq request,
            OAuth2Authentication auth) {
        if (CollectionUtils.isEmpty(request.getTags())) {
            return buildClientErrorResult("tags is required");
        }
        String operator = getOperator(auth);

        try {
            String appPackageVersion = appPackageTaskProvider.nextLatestVersion(
                    AppPackageTaskNextLatestVersionReq.builder().appId(appId).build(), operator
            );
            request.setVersion(appPackageVersion);
            List<AppComponentDTO> appComponents = appComponentProvider.list(
                    AppComponentQueryReq.builder().appId(appId).build(), operator
            );
            if (CollectionUtils.isEmpty(appComponents)) {
                throw new AppException(AppErrorCode.INVALID_USER_ARGS, "missing package component");
            }

            List<ComponentBinder> components = new ArrayList<>();
            for (AppComponentDTO appComponent : appComponents) {
                ComponentBinder componentBinder = ComponentBinder.builder()
                        .componentType(appComponent.getComponentType())
                        .componentName(appComponent.getComponentName())
                        .componentLabel(appComponent.getComponentLabel())
                        .version(appComponent.getComponentVersion())
                        .build();
                if (appComponent.getComponentType().isKubernetesMicroservice()) {
                    componentBinder.setBranch(DefaultConstant.DEFAULT_REPO_BRANCH);
                    List<ComponentPackageVersionItemDTO> componentVersionList = componentPackageProvider
                            .latestVersions(
                                    ComponentPackageLatestVersionListReq.builder()
                                            .appId(appId)
                                            .componentType(appComponent.getComponentType().toString())
                                            .componentName(appComponent.getComponentName())
                                            .build(),
                                    operator);
                    if (CollectionUtils.isEmpty(componentVersionList)) {
                        return buildClientErrorResult(appComponent.getComponentName() + " ?????????????????????");
                    }
                    componentBinder.setVersion(componentVersionList.get(0).getName());
                } else if (appComponent.getComponentType().isHelm()) {
                    componentBinder.setBranch(DefaultConstant.DEFAULT_REPO_BRANCH);
                    List<ComponentPackageVersionItemDTO> componentVersionList = componentPackageProvider
                            .latestVersions(
                                    ComponentPackageLatestVersionListReq.builder()
                                            .appId(appId)
                                            .componentType(appComponent.getComponentType().toString())
                                            .componentName(appComponent.getComponentName())
                                            .build(),
                                    operator);
                    if (CollectionUtils.isEmpty(componentVersionList)) {
                        return buildClientErrorResult(appComponent.getComponentName() + " ?????????????????????");
                    }
                    componentBinder.setVersion(componentVersionList.get(0).getName());
                }
                components.add(componentBinder);
            }

            if (request.isDevelop()) {
                ComponentBinder developmentMeta = ComponentBinder.builder()
                        .componentType(ComponentTypeEnum.INTERNAL_ADDON)
                        .componentName(INTERNAL_ADDON_DEVELOPMENT_META)
                        .componentLabel("Development Meta")
                        .version(DefaultConstant.INIT_VERSION)
                        .build();
                components.add(developmentMeta);

                ComponentBinder appMeta = ComponentBinder.builder()
                        .componentType(ComponentTypeEnum.INTERNAL_ADDON)
                        .componentName(INTERNAL_ADDON_APP_META)
                        .componentLabel("App Meta")
                        .version(DefaultConstant.INIT_VERSION)
                        .build();
                components.add(appMeta);
            }

            request.setComponents(components);
            request.setAppId(appId);
            AppPackageTaskCreateRes response = appPackageTaskProvider.create(request, getOperator(auth));
            return buildSucceedResult(response);
        } catch (AppException e) {
            return buildResult(e.getErrorCode().getCode(), e.getErrorMessage(), e.getErrorData());
        }
    }
}
