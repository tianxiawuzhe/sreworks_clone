package dynamicscripts

import com.alibaba.fastjson.JSONObject
import com.alibaba.tesla.appmanager.autoconfig.PackageProperties
import com.alibaba.tesla.appmanager.common.constants.DefaultConstant
import com.alibaba.tesla.appmanager.common.enums.DynamicScriptKindEnum
import com.alibaba.tesla.appmanager.common.exception.AppErrorCode
import com.alibaba.tesla.appmanager.common.exception.AppException
import com.alibaba.tesla.appmanager.common.pagination.Pagination
import com.alibaba.tesla.appmanager.common.util.PackageUtil
import com.alibaba.tesla.appmanager.common.util.StringUtil
import com.alibaba.tesla.appmanager.common.util.ZipUtil
import com.alibaba.tesla.appmanager.domain.core.StorageFile
import com.alibaba.tesla.appmanager.domain.req.componentpackage.BuildComponentHandlerReq
import com.alibaba.tesla.appmanager.domain.res.componentpackage.LaunchBuildComponentHandlerRes
import com.alibaba.tesla.appmanager.meta.helm.repository.condition.HelmMetaQueryCondition
import com.alibaba.tesla.appmanager.meta.helm.repository.domain.HelmMetaDO
import com.alibaba.tesla.appmanager.meta.helm.service.HelmMetaService
import com.alibaba.tesla.appmanager.meta.k8smicroservice.repository.condition.K8sMicroserviceMetaQueryCondition
import com.alibaba.tesla.appmanager.meta.k8smicroservice.repository.domain.K8sMicroServiceMetaDO
import com.alibaba.tesla.appmanager.meta.k8smicroservice.service.K8sMicroserviceMetaService
import com.alibaba.tesla.appmanager.server.factory.JinjaFactory
import com.alibaba.tesla.appmanager.server.repository.condition.AppAddonQueryCondition
import com.alibaba.tesla.appmanager.server.repository.domain.AppAddonDO
import com.alibaba.tesla.appmanager.server.service.appaddon.AppAddonService
import com.alibaba.tesla.appmanager.server.service.componentpackage.handler.BuildComponentHandler
import com.alibaba.tesla.appmanager.server.storage.Storage
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/**
 * ?????? DevelopmentMeta Internal Addon Groovy Handler
 *
 * @author yaoxing.gyx@alibaba-inc.com
 */
class DefaultBuildInternalAddonDevelopmentMetaHandler implements BuildComponentHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultBuildInternalAddonDevelopmentMetaHandler.class)

    /**
     * ???????????? Handler ??????
     */
    public static final String KIND = DynamicScriptKindEnum.BUILD_IA_DEVELOPMENT_META_COMPONENT.toString()

    /**
     * ???????????? Handler ??????
     */
    public static final String NAME = DefaultConstant.DEFAULT_GROOVY_HANDLER

    /**
     * ???????????? Handler ??????
     */
    public static final Integer REVISION = 8

    private static final String TEMPLATE_INTERNAL_ADDON_DEVELOPMENTMETA_FILENAME = "default_internal_addon_developmentmeta.tpl"
    private static final String EXPORT_OPTION_FILE = "option.json"

    @Autowired
    private PackageProperties packageProperties

    @Autowired
    private Storage storage

    @Autowired
    private K8sMicroserviceMetaService k8sMicroserviceMetaService

    @Autowired
    private HelmMetaService helmMetaService

    @Autowired
    private AppAddonService appAddonService

    /**
     * ?????????????????? Component Package
     *
     * @param request ComponentPackage ??????????????????
     * @return ???????????????
     */
    @Override
    LaunchBuildComponentHandlerRes launch(BuildComponentHandlerReq request) {
        def appId = request.getAppId()
        def componentType = request.getComponentType()
        def componentName = request.getComponentName()
        def version = request.getVersion()
        def options = request.getOptions()

        // ????????????????????????????????????????????????????????? meta ???????????????????????????
        def packageDir
        try {
            packageDir = Files.createTempDirectory("appmanager_component_package_")
            packageDir.toFile().deleteOnExit()
        } catch (IOException e) {
            throw new AppException(AppErrorCode.UNKNOWN_ERROR, "cannot create temp directory", e)
        }
        def exportPath = Paths.get(packageDir.toString(), EXPORT_OPTION_FILE)

        // ???????????????????????????
        def buildData = new JSONObject();

        // microservice
        buildData.put("microservices", null)
        Pagination<K8sMicroServiceMetaDO> k8sMicroservicePagination = k8sMicroserviceMetaService.list(
                K8sMicroserviceMetaQueryCondition.builder().appId(appId).withBlobs(true).build())
        if (!k8sMicroservicePagination.isEmpty()) {
            buildData.put("microservices", k8sMicroservicePagination.getItems())
        }

        // helm
        buildData.put("helms", null)
        Pagination<HelmMetaDO> helmPagination = helmMetaService.list(HelmMetaQueryCondition.builder().appId(appId).withBlobs(true).build())
        if (!helmPagination.isEmpty()) {
            buildData.put("helms", helmPagination.getItems())
        }

        // addons
        buildData.put("addons", null)
        Pagination<AppAddonDO> addons = appAddonService.list(AppAddonQueryCondition.builder().appId(appId).build())
        if (!addons.isEmpty()) {
            buildData.put("addons", addons.getItems())
        }

        def data = buildData.toJSONString()
        log.info("development meta export {}", data)
        FileUtils.writeStringToFile(exportPath.toFile(), data, Charset.defaultCharset())

        // ?????? meta.yaml ?????????????????? packageDir ???????????????
        def jinjava = JinjaFactory.getJinjava()
        def template = getTemplate(TEMPLATE_INTERNAL_ADDON_DEVELOPMENTMETA_FILENAME)
        def optionsClone = (JSONObject) options.clone()
        options.put("appId", appId)
        options.put("componentType", componentType)
        options.put("componentName", componentName)
        options.put("version", version)
        options.putIfAbsent("options", optionsClone)
        def metaYamlContent = jinjava.render(template, options)
        def metaYamlFile = Paths.get(packageDir.toString(), "meta.yaml").toFile()
        FileUtils.writeStringToFile(metaYamlFile, metaYamlContent, StandardCharsets.UTF_8)
        log.info("meta yaml config has rendered||appId={}||componentType={}||componentName={}||packageVersion={}",
                appId, componentType, componentName, version)

        // ??? packageDir ????????? zip ??????
        String zipPath = packageDir.resolve("app_package.zip").toString()
        Files.list(packageDir).map({ p -> p.toFile() }).forEach({ item ->
            if (item.isDirectory()) {
                ZipUtil.zipDirectory(zipPath, item)
            } else {
                ZipUtil.zipFile(zipPath, item)
            }
        })
        def targetFileMd5 = StringUtil.getMd5Checksum(zipPath)
        log.info("zip file has generated||appId={}||componentType={}||componentName={}||packageVersion={}||" +
                "zipPath={}||md5={}", appId, componentType, componentName, version,
                zipPath, targetFileMd5)

        // ?????????????????? Storage ???
        String bucketName = packageProperties.getBucketName()
        String remotePath = PackageUtil
                .buildComponentPackageRemotePath(appId, componentType, componentName, version)
        storage.putObject(bucketName, remotePath, zipPath)
        log.info("component package has uploaded to storage||bucketName={}||" +
                "remotePath={}||localPath={}", bucketName, remotePath, zipPath)

        // ?????????????????? (???????????????)
        try {
            FileUtils.deleteDirectory(packageDir.toFile())
        } catch (Exception ignored) {
            log.warn("cannot delete component package build directory||directory={}", packageDir.toString())
        }
        LaunchBuildComponentHandlerRes res = LaunchBuildComponentHandlerRes.builder()
                .logContent("get zip file from productops succeed")
                .storageFile(new StorageFile(bucketName, remotePath))
                .packageMetaYaml(metaYamlContent)
                .packageMd5(targetFileMd5)
                .build()
        return res
    }

    /**
     * ????????????????????? resources ?????? Jinja ??????
     * @param templateName ????????????
     * @return ????????????
     */
    private static String getTemplate(String templateName) {
        def config = new ClassPathResource("jinja/" + templateName)
        return IOUtils.toString(config.getInputStream(), StandardCharsets.UTF_8)
    }
}
