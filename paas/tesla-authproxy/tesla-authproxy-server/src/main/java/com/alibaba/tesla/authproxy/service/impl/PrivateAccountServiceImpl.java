package com.alibaba.tesla.authproxy.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import com.alibaba.tesla.authproxy.AuthProperties;
import com.alibaba.tesla.authproxy.Constants;
import com.alibaba.tesla.authproxy.lib.exceptions.AuthProxyThirdPartyError;
import com.alibaba.tesla.authproxy.lib.exceptions.PrivateAasVerifyFailed;
import com.alibaba.tesla.authproxy.lib.exceptions.PrivateInternalError;
import com.alibaba.tesla.authproxy.lib.exceptions.PrivateValidationError;
import com.alibaba.tesla.authproxy.model.ConfigDO;
import com.alibaba.tesla.authproxy.model.OplogDO;
import com.alibaba.tesla.authproxy.model.UserCallbackDO;
import com.alibaba.tesla.authproxy.model.UserDO;
import com.alibaba.tesla.authproxy.model.mapper.ConfigMapper;
import com.alibaba.tesla.authproxy.model.mapper.OplogMapper;
import com.alibaba.tesla.authproxy.model.mapper.UserCallbackMapper;
import com.alibaba.tesla.authproxy.model.mapper.UserMapper;
import com.alibaba.tesla.authproxy.outbound.aas.AasClient;
import com.alibaba.tesla.authproxy.outbound.aas.AasLoginResult;
import com.alibaba.tesla.authproxy.outbound.aas.AasLoginStatusEnum;
import com.alibaba.tesla.authproxy.outbound.aas.AasUserInfo;
import com.alibaba.tesla.authproxy.outbound.acs.AcsClientFactory;
import com.alibaba.tesla.authproxy.outbound.oam.OamClient;
import com.alibaba.tesla.authproxy.service.PrivateAccountService;
import com.alibaba.tesla.authproxy.service.PrivateSmsService;
import com.alibaba.tesla.authproxy.service.TeslaUserService;
import com.alibaba.tesla.authproxy.service.constant.UserCallbackTypeEnum;
import com.alibaba.tesla.authproxy.util.LocaleUtil;
import com.alibaba.tesla.authproxy.util.PasswordUtil;
import com.alibaba.tesla.authproxy.util.StringUtil;
import com.alibaba.tesla.authproxy.web.input.PrivateAccountAddParam;
import com.alibaba.tesla.authproxy.web.input.PrivateAccountDeleteParam;
import com.alibaba.tesla.authproxy.web.input.PrivateAccountInfoChangeParam;
import com.alibaba.tesla.authproxy.web.input.PrivateAccountLoginParam;
import com.alibaba.tesla.authproxy.web.input.PrivateAccountLoginSmsParam;
import com.alibaba.tesla.authproxy.web.input.PrivateAccountValidationParam;
import com.alibaba.tesla.authproxy.web.output.PrivateAccountAddResult;
import com.alibaba.tesla.authproxy.web.output.PrivateAccountAliyunListResult;
import com.alibaba.tesla.authproxy.web.output.PrivateAccountAliyunResult;
import com.alibaba.tesla.authproxy.web.output.PrivateAccountListResult;
import com.alibaba.tesla.authproxy.web.output.PrivateAccountListResult.PrivateAccountItem;
import com.alibaba.tesla.authproxy.web.output.PrivateAccountLoginOptionResult;
import com.alibaba.tesla.authproxy.web.output.PrivateAccountLoginSmsResult;
import com.alibaba.tesla.authproxy.web.output.PrivateAccountValidationResult;
import com.alibaba.tesla.common.base.util.TeslaGsonUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author yaoxing.gyx@alibaba-inc.com
 */
@Service
@Slf4j
public class PrivateAccountServiceImpl implements PrivateAccountService {

    private static final String TEMP_PASSWORD_PREFIX = "_TEMP_";

    private static final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    @Autowired
    private LocaleUtil locale;

    @Autowired
    private TeslaUserService teslaUserService;
    //
    //@Autowired
    //private LocaleResolver localeResolver;

    @Autowired
    private ConfigMapper configMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserCallbackMapper userCallbackMapper;

    @Autowired
    private OplogMapper oplogMapper;

    @Autowired
    private AcsClientFactory acsClientFactory;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private AasClient aasClient;

    @Autowired
    private OamClient oamClient;

    @Autowired
    private OkHttpClient httpClient;

    @Autowired
    private PrivateSmsService smsService;

    private ObjectMapper jsonMapper = new ObjectMapper();

    private PasswordUtil passwordUtil = new PasswordUtil.PasswordUtilBuilder()
        .useDigits(true)
        .useLower(true)
        .useUpper(true)
        .build();

    private Gson gson = new GsonBuilder().serializeNulls().create();

    /**
     * ?????? aliyunId ??????????????? aliyunPk
     *
     * @param aliyunId aliyunId
     * @return aliyunPk ??????
     * @throws PrivateValidationError ?????????????????????
     */
    @Override
    public PrivateAccountAliyunResult getAliyunPkById(String aliyunId) throws PrivateValidationError {
        //AliyunidClient client = acsClientFactory.getAliyunidClient();
        return PrivateAccountAliyunResult.builder()
            .aliyunId(aliyunId)
            .build();
    }

    /**
     * ?????? aliyunPk ??????????????? aliyunId
     *
     * @param aliyunPk aliyunPk
     * @return aliyunId ?????????
     * @throws PrivateValidationError ?????????????????????
     */
    @Override
    public PrivateAccountAliyunResult getAliyunIdByPk(String aliyunPk) throws PrivateValidationError {
        throw new RuntimeException("not support");
        //AliyunidClient client = acsClientFactory.getAliyunidClient();
        //try {
        //    List<OAuthPair> list = new LinkedList<>();
        //    OAuthPair oAuthPair = new OAuthPair("kp", aliyunPk);
        //    list.add(oAuthPair);
        //    String result = client.callApi(authProperties.getAasInnerEndpoint() + "/innerapi/id/load_by_kp", list);
        //    if (StringUtils.isEmpty(result) || result.contains("errorCode") || result.contains("errorMsg")) {
        //        throw new PrivateValidationError("aliyunPk", "aliyunId not exist");
        //    }
        //    String aliyunId = JSONObject.parseObject(result).getString("aliyunID");
        //    return PrivateAccountAliyunResult.builder()
        //        .aliyunId(aliyunId)
        //        .aliyunPk(aliyunPk)
        //        .build();
        //} catch (OAuthException e) {
        //    log.error("Error when get aliyun id by pk||aliyunPk={}||exception={}",
        //        aliyunPk, ExceptionUtils.getStackTrace(e));
        //    throw new PrivateValidationError("aliyunPk", e.getMessage());
        //}
    }

    /**
     * ?????? aliyunId ??????????????? aliyunPk (??????)
     *
     * @param aliyunIds aliyunId ??????
     * @return aliyunPk ??????
     * @throws PrivateValidationError ?????????????????????
     */
    @Override
    public PrivateAccountAliyunListResult getAliyunPkByIds(String aliyunIds) throws PrivateValidationError {
        throw new RuntimeException("not support");
        //AliyunidClient client = acsClientFactory.getAliyunidClient();
        //List<PrivateAccountAliyunListResult.Item> items = new ArrayList<>();
        //for (String aliyunId : aliyunIds.split(",")) {
        //    items.add(PrivateAccountAliyunListResult.Item.builder()
        //        .aliyunId(aliyunId)
        //        .build());
        //}
        //return PrivateAccountAliyunListResult.builder().items(items).build();
    }

    /**
     * ?????? aliyunPk ??????????????? aliyunId (??????)
     *
     * @param aliyunPks aliyunPk ??????
     * @return aliyunId ??????
     * @throws PrivateValidationError ?????????????????????
     */
    @Override
    public PrivateAccountAliyunListResult getAliyunIdByPks(String aliyunPks) throws PrivateValidationError {
        throw new RuntimeException("not support");
        //AliyunidClient client = acsClientFactory.getAliyunidClient();
        //List<PrivateAccountAliyunListResult.Item> items = new ArrayList<>();
        //for (String aliyunPk : aliyunPks.split(",")) {
        //    try {
        //        List<OAuthPair> list = new LinkedList<>();
        //        OAuthPair oAuthPair = new OAuthPair("kp", aliyunPk);
        //        list.add(oAuthPair);
        //        String result = client.callApi(authProperties.getAasInnerEndpoint() + "/innerapi/id/load_by_kp", list);
        //        if (StringUtils.isEmpty(result) || result.contains("errorCode") || result.contains("errorMsg")) {
        //            throw new PrivateValidationError("aliyunPk", "aliyunId not exist");
        //        }
        //        String aliyunId = JSONObject.parseObject(result).getString("aliyunID");
        //        items.add(PrivateAccountAliyunListResult.Item.builder()
        //            .aliyunId(aliyunPk)
        //            .aliyunPk(aliyunId)
        //            .build());
        //    } catch (OAuthException e) {
        //        log.error("Error when get aliyun id from aliyun pk||aliyunPk={}||exception={}",
        //            aliyunPk, ExceptionUtils.getStackTrace(e));
        //        throw new PrivateValidationError("aliyunPk", e.getMessage());
        //    }
        //}
        //return PrivateAccountAliyunListResult.builder().items(items).build();
    }

    /**
     * ???????????????????????????
     */
    @Override
    public PrivateAccountListResult accountListBySearch(String search) {
        ConfigDO validationConfig = configMapper.getByName(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION);
        ConfigDO smsEndpointConfig = configMapper.getByName(Constants.CONFIG_PRIVATE_SMS_ENDPOINT);
        ConfigDO smsTokenConfig = configMapper.getByName(Constants.CONFIG_PRIVATE_SMS_TOKEN);
        List<UserDO> users = teslaUserService.selectByName(search);
        PrivateAccountListResult result = new PrivateAccountListResult();
        if (validationConfig == null) {
            log.warn("Cannot getOamClient private account validation from config table, use password default");
            result.setValidation(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION_PASSWORD);
        } else {
            result.setValidation(validationConfig.getValue());
        }
        if (smsEndpointConfig != null && smsTokenConfig != null && smsEndpointConfig.getValue().length() > 0
            && smsTokenConfig.getValue().length() > 0) {
            result.setSmsGateway(true);
        } else {
            result.setSmsGateway(false);
        }
        LocalDateTime now = LocalDateTime.now();
        for (UserDO user : users) {
            PrivateAccountItem item = new PrivateAccountItem();
            item.setAliyunId(user.getLoginName());
            item.setAccessKeyId(user.getAccessKeyId());
            item.setAccessKeySecret(user.getAccessKeySecret());
            item.setCreateTime(user.getGmtCreate());
            item.setPhone(user.getPhone());
            item.setFirstLogin(user.getIsFirstLogin() == 1 ? Boolean.TRUE : Boolean.FALSE);
            item.setIsLock(user.getIsLocked() == 1 ? Boolean.TRUE : Boolean.FALSE);
            LocalDateTime passwordChangeDateTime = getUserNextPasswordChangeDate(user.getLoginName());
            item.setPasswordChangeNextTime(
                Date.from(passwordChangeDateTime.atZone(ZoneId.systemDefault()).toInstant()));
            item.setPasswordChangeRestDays((int)ChronoUnit.DAYS.between(now, passwordChangeDateTime));
            item.setIsImmutable(user.getIsImmutable() == 1 ? Boolean.TRUE : Boolean.FALSE);
            result.addResult(item);
        }
        return result;
    }

    /**
     * ??????????????????????????? (???????????????)
     */
    @Override
    public PrivateAccountListResult accountListByUser(UserDO userDo) {
        ConfigDO validationConfig = configMapper.getByName(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION);
        ConfigDO smsEndpointConfig = configMapper.getByName(Constants.CONFIG_PRIVATE_SMS_ENDPOINT);
        ConfigDO smsTokenConfig = configMapper.getByName(Constants.CONFIG_PRIVATE_SMS_TOKEN);
        PrivateAccountListResult result = new PrivateAccountListResult();
        if (validationConfig == null) {
            log.warn("Cannot getOamClient private account validation from config table, use password default");
            result.setValidation(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION_PASSWORD);
        } else {
            result.setValidation(validationConfig.getValue());
        }
        if (smsEndpointConfig != null && smsTokenConfig != null && smsEndpointConfig.getValue().length() > 0
            && smsTokenConfig.getValue().length() > 0) {
            result.setSmsGateway(true);
        } else {
            result.setSmsGateway(false);
        }
        LocalDateTime now = LocalDateTime.now();
        PrivateAccountItem item = new PrivateAccountItem();
        item.setAliyunId(userDo.getLoginName());
        item.setAccessKeyId(userDo.getAccessKeyId());
        item.setAccessKeySecret(userDo.getAccessKeySecret());
        item.setCreateTime(userDo.getGmtCreate());
        item.setPhone(userDo.getPhone());
        item.setFirstLogin(userDo.getIsFirstLogin() == 1 ? Boolean.TRUE : Boolean.FALSE);
        item.setIsLock(userDo.getIsLocked() == 1 ? Boolean.TRUE : Boolean.FALSE);
        LocalDateTime passwordChangeDateTime = getUserNextPasswordChangeDate(userDo.getLoginName());
        item.setPasswordChangeNextTime(Date.from(passwordChangeDateTime.atZone(ZoneId.systemDefault()).toInstant()));
        item.setPasswordChangeRestDays((int)ChronoUnit.DAYS.between(now, passwordChangeDateTime));
        item.setIsImmutable(userDo.getIsImmutable() == 1 ? Boolean.TRUE : Boolean.FALSE);
        result.addResult(item);
        return result;
    }

    /**
     * ?????????????????????
     */
    @Override
    public PrivateAccountAddResult accountAdd(PrivateAccountAddParam param, HttpServletRequest request)
        throws AuthProxyThirdPartyError, PrivateValidationError, PrivateInternalError {
        throw new RuntimeException("not  support");
        //String aliyunId = param.getAliyunId();
        //String password = param.getPassword();
        //String phone = validatePhoneNumber(param.getPhone(), request);
        //Byte isFirstLogin = (byte)(param.getPasswordChangeRequired() ? 1 : 0);
        //Date now = new Date();
        //
        //// ????????????
        //String aliyunPk;
        //Boolean alreadyExist = Boolean.FALSE;
        //try {
        //    aliyunPk = aasClient.createAccount(aliyunId);
        //} catch (ClientException e) {
        //    switch (e.getErrCode()) {
        //        case "InvalidAliyunId.Confict":
        //            UserDO user = teslaUserService.getUserByLoginName(aliyunId);
        //            // ??????????????????
        //            if (null == user) {
        //                throw new PrivateValidationError("aliyunId", locale.msg("private.aas.createAccount.confict"));
        //            }
        //            UserStatusEnum userStatus = UserStatusEnum.build(user.getStatus());
        //            // ?????????????????????????????????????????????????????????????????? AccessKey ???????????????????????????????????????????????? AccessKey
        //            if (userStatus.equals(UserStatusEnum.NON_COMPLETE_ACCESSKEY) ||
        //                userStatus.equals(UserStatusEnum.NON_COMPLETE_CALLBACK)) {
        //                try {
        //                    aasClient.deleteAccessKey(user.getAliyunPk());
        //                } catch (ClientException clientException) {
        //                    AuthProxyThirdPartyError se = new AuthProxyThirdPartyError(Constants.THIRD_PARTY_AAS,
        //                        "DeleteAccessKey failed: " + clientException.getMessage());
        //                    se.initCause(e);
        //                    throw se;
        //                }
        //            }
        //            // ??????????????? ALIVE ???????????????????????? alreadyExist ?????? true
        //            if (!userStatus.equals(UserStatusEnum.ALIVE)) {
        //                aliyunPk = user.getAliyunPk();
        //                alreadyExist = Boolean.TRUE;
        //                break;
        //            }
        //            throw new PrivateValidationError("aliyunId", locale.msg("private.aas.createAccount.confict"));
        //        case "InvalidAliyunId.Malformed":
        //            throw new PrivateValidationError("aliyunId", locale.msg("private.aas.createAccount.malformed"));
        //        case "InvalidAliyunId.WrongPrefix":
        //            throw new PrivateValidationError("aliyunId", locale.msg("private.aas.createAccount.wrongPrefix"));
        //        case "InvalidAliyunId.HasForbiddenWord":
        //            throw new PrivateValidationError("aliyunId",
        //                locale.msg("private.aas.createAccount.hasForbiddenWord"));
        //        default:
        //            AuthProxyThirdPartyError se = new AuthProxyThirdPartyError(Constants.THIRD_PARTY_AAS,
        //                "CreateAccount failed: " + e.getMessage());
        //            se.initCause(e);
        //            throw se;
        //    }
        //}
        //log.info("Account {} add process, aas account created, aliyunPk={}", aliyunId, aliyunPk);
        //
        //// ????????????(????????????????????????????????????????????????)
        //if (!alreadyExist) {
        //    String randomPassword = TEMP_PASSWORD_PREFIX + passwordUtil.generate(10);
        //    try {
        //        aasClient.updatePassword(aliyunPk, randomPassword);
        //    } catch (ClientException e) {
        //        AuthProxyThirdPartyError se = new AuthProxyThirdPartyError(Constants.THIRD_PARTY_AAS,
        //            "UpdatePassword failed: " + e.getMessage());
        //        se.initCause(e);
        //        throw se;
        //    }
        //    // ?????????????????????????????????
        //    UserDO user = new UserDO();
        //    user.setTenantId(Constants.DEFAULT_TENANT_ID);
        //    user.setBid(authProperties.getAasDefaultBid());
        //    user.setEmpId(aliyunPk);
        //    user.setAliyunPk(aliyunPk);
        //    user.setLoginName(aliyunId);
        //    user.setNickName(aliyunId);
        //    user.setPhone(phone);
        //    user.setGmtCreate(now);
        //    user.setGmtModified(now);
        //    user.setIsFirstLogin(isFirstLogin);
        //    user.setIsLocked((byte)0);
        //    user.setAccessKeyId("");
        //    user.setAccessKeySecret("");
        //    user.setStatus(UserStatusEnum.NON_COMPLETE_PASSWORD.toInt());
        //    user.setUserId(UserUtil.getUserId(user));
        //    teslaUserService.save(user);
        //    log.info("Account {} add process, random password has updated in aas", aliyunId);
        //}
        //
        //UserDO user = teslaUserService.getUserByLoginName(aliyunId);
        //if (null == user) {
        //    throw new PrivateInternalError("Cannot get user by login name " + aliyunId);
        //}
        //
        //// ????????????(????????????)
        //try {
        //    aasClient.updatePassword(aliyunPk, password);
        //} catch (ClientException e) {
        //    switch (e.getErrCode()) {
        //        case "MissingParameter.NewPassword":
        //            throw new PrivateValidationError("password",
        //                locale.msg("private.aas.updatePassword.missingNewPassword"));
        //        case "InvalidNewPassword.Malformed":
        //            throw new PrivateValidationError("password", locale.msg("private.aas.updatePassword.malformed"));
        //        case "InvalidNewPassword.WrongLength":
        //            throw new PrivateValidationError("password", locale.msg("private.aas.updatePassword.wrongLength"));
        //        default:
        //            AuthProxyThirdPartyError se = new AuthProxyThirdPartyError(Constants.THIRD_PARTY_AAS,
        //                "UpdatePassword failed: " + e.getMessage());
        //            se.initCause(e);
        //            throw se;
        //    }
        //}
        //user.setGmtCreate(now);
        //user.setGmtModified(now);
        //user.setPhone(phone);
        //user.setIsFirstLogin(isFirstLogin);
        //user.setIsLocked((byte)0);
        //user.setStatus(UserStatusEnum.NON_COMPLETE_ACCESSKEY.toInt());
        //teslaUserService.update(user);
        //log.info("Account {} add process, user password has updated", aliyunId);
        //
        ////// ?????? Access Key ??????????????????
        ////AccessKey accessKey;
        ////try {
        ////    accessKey = aasClient.createAccessKey(aliyunPk);
        ////} catch (ClientException e) {
        ////    AuthProxyThirdPartyError se = new AuthProxyThirdPartyError(Constants.THIRD_PARTY_AAS,
        ////        "CreateAccessKey failed: " + e.getMessage());
        ////    se.initCause(e);
        ////    throw se;
        ////}
        ////user.setGmtModified(now);
        //////user.setAccessKeyId(accessKey.getAccessKeyId());
        ////user.setAccessKeySecret(accessKey.getAccessKeySecret());
        //user.setStatus(UserStatusEnum.NON_COMPLETE_CALLBACK.toInt());
        //teslaUserService.update(user);
        //log.info("Account {} add process, accessKeyId/accessKeySecret has generated", aliyunId);
        //
        //// ??? OAM ??????????????? (by ??????)
        //try {
        //    oamClient.getOamUserByUsername(user.getAliyunPk());
        //} catch (ClientException e) {
        //    AuthProxyThirdPartyError se = new AuthProxyThirdPartyError(Constants.THIRD_PARTY_OAM,
        //        "GetOamUserByUserName failed: " + e.getMessage());
        //    se.initCause(e);
        //    throw se;
        //}
        //
        //// ?????????????????????????????????
        //String triggerType = UserCallbackTypeEnum.USER_CREATED.toString();
        //List<UserCallbackDO> callbacks = userCallbackMapper.selectByTriggerType(triggerType);
        //if (null != callbacks) {
        //    Map<String, String> requestData = new HashMap<>();
        //    requestData.put("pk", aliyunPk);
        //    requestData.put("aliyunId", aliyunId);
        //    //requestData.put("accessKeyId", accessKey.getAccessKeyId());
        //    //requestData.put("accessKeySecret", accessKey.getAccessKeySecret());
        //    requestData.put("phone", phone);
        //    String requestBodyStr;
        //    try {
        //        requestBodyStr = jsonMapper.writeValueAsString(requestData);
        //    } catch (JsonProcessingException e) {
        //        throw new PrivateInternalError(String.format("Cannot build request data, requestData=%s",
        //            gson.toJson(requestData)));
        //    }
        //    for (UserCallbackDO callback : callbacks) {
        //        String url = callback.getUrl();
        //        Request req = new Request.Builder()
        //            .url(url)
        //            .post(RequestBody.create(JSON_MEDIA_TYPE, requestBodyStr))
        //            .build();
        //        Response resp;
        //        String responseString = "";
        //        try {
        //            resp = httpClient.newCall(req).execute();
        //            ResponseBody responseBody = resp.body();
        //            if (null != responseBody) {
        //                responseString = responseBody.string();
        //            }
        //            JsonNode result = jsonMapper.readTree(responseString);
        //            Integer code = result.get("code").asInt(-1);
        //            if (!code.equals(200)) {
        //                throw new IOException(String.format("Invalid response from callback url %s: %s",
        //                    url, responseString));
        //            }
        //            JsonNode data = result.at("/data");
        //            if (!data.isMissingNode() && data.isObject()) {
        //                Integer status = data.get("status").asInt(200);
        //                if (!status.equals(200)) {
        //                    throw new IOException(String.format("Invalid response from callback url %s: %s",
        //                        url, responseString));
        //                }
        //            }
        //        } catch (IOException e) {
        //            log.error("Callback failed when send request {} with body {}", url, requestBodyStr);
        //            continue;
        //        }
        //        log.info("Account {} add process, post callback url {}, response={}", aliyunId, url, responseString);
        //    }
        //}
        //user.setGmtModified(now);
        //user.setStatus(UserStatusEnum.ALIVE.toInt());
        //teslaUserService.update(user);
        //log.info("Account {} add process, alive", aliyunId);
        //
        //// ?????? OP Log
        //insertPasswordChangeOpLog(user.getLoginName());
        //
        //// ????????????????????????????????????
        //PrivateAccountAddResult result = new PrivateAccountAddResult();
        //result.setAliyunId(user.getLoginName());
        //result.setAccessKeyId(user.getAccessKeyId());
        //result.setAccessKeySecret(user.getAccessKeySecret());
        //result.setCreateTime(user.getGmtCreate());
        //result.setPhone(user.getPhone());
        //result.setFirstLogin(user.getIsFirstLogin() == 1 ? Boolean.TRUE : Boolean.FALSE);
        //result.setLock(user.getIsLocked() == 1 ? Boolean.TRUE : Boolean.FALSE);
        //LocalDateTime passwordChangeDateTime = getUserNextPasswordChangeDate(user.getLoginName());
        //result.setPasswordChangeNextTime(Date.from(passwordChangeDateTime.atZone(ZoneId.systemDefault()).toInstant()));
        //result.setPasswordChangeRestDays((int)ChronoUnit.DAYS.between(LocalDateTime.now(), passwordChangeDateTime));
        //return result;
    }

    /**
     * ???????????????????????????
     */
    @Override
    public void lock(String aliyunId, Boolean isLock) throws PrivateValidationError {
        UserDO userDo = teslaUserService.getUserByLoginName(aliyunId);
        if (null == userDo) {
            throw new PrivateValidationError("aliyunId",
                locale.msg("private.account.lock.validation.aliyunId.notExist"));
        }

        // ??????????????????????????????
        Date now = new Date();
        userDo.setGmtModified(now);
        userDo.setIsLocked((byte)(isLock ? 1 : 0));
        teslaUserService.update(userDo);
    }

    /**
     * ??????????????????
     */
    @Override
    public void passwordChange(String aliyunId, String password)
        throws PrivateValidationError, AuthProxyThirdPartyError {
        Date now = new Date();
        UserDO userDo = teslaUserService.getUserByLoginName(aliyunId);
        if (null == userDo) {
            throw new PrivateValidationError("nonExistField",
                locale.msg("private.account.password.change.validation.aliyunId.notExist"));
        }
        validatePassword(password);

        //try {
        //    aasClient.updatePassword(userDo.getAliyunPk(), password);
        //} catch (ClientException e) {
        //    switch (e.getErrCode()) {
        //        case "MissingParameter.NewPassword":
        //            throw new PrivateValidationError("password",
        //                locale.msg("private.aas.updatePassword.missingNewPassword"));
        //        case "InvalidNewPassword.Malformed":
        //            throw new PrivateValidationError("password", locale.msg("private.aas.updatePassword.malformed"));
        //        case "InvalidNewPassword.WrongLength":
        //            throw new PrivateValidationError("password", locale.msg("private.aas.updatePassword.wrongLength"));
        //        default:
        //            AuthProxyThirdPartyError se = new AuthProxyThirdPartyError(Constants.THIRD_PARTY_AAS,
        //                "UpdatePassword failed: " + e.getMessage());
        //            se.initCause(e);
        //            throw se;
        //    }
        //}

        userDo.setIsFirstLogin((byte)0);
        userDo.setGmtModified(now);
        teslaUserService.update(userDo);
        log.info("User {} password has changed into {}", aliyunId, password);

        // ?????? OP Log
        insertPasswordChangeOpLog(userDo.getLoginName());
    }

    /**
     * ??????????????????
     */
    @Override
    public void infoChange(PrivateAccountInfoChangeParam param, HttpServletRequest request)
        throws PrivateValidationError {
        String aliyunId = param.getAliyunId();
        String phone = validatePhoneNumber(param.getPhone(), request);

        Date now = new Date();
        UserDO user = teslaUserService.getUserByLoginName(aliyunId);
        if (null == user) {
            throw new PrivateValidationError(
                "aliyunId", locale.msg("private.account.info.change.validation.aliyunId.notExist")
            );
        }
        user.setPhone(phone);
        user.setGmtModified(now);
        teslaUserService.update(user);
    }

    /**
     * ????????????
     */
    @Override
    public void delete(PrivateAccountDeleteParam param) throws PrivateValidationError, AuthProxyThirdPartyError {
        String aliyunId = param.getAliyunId();
        UserDO user = teslaUserService.getUserByLoginName(aliyunId);
        if (null == user || user.getStatus() == -1) {
            throw new PrivateValidationError(
                "aliyunId", locale.msg("private.account.delete.validation.aliyunId.notExist")
            );
        }
        String[] superUsers = authProperties.getAasSuperUser().split(",");
        for (String superUser : superUsers) {
            if (user.getLoginName().equals(superUser.trim())) {
                throw new PrivateValidationError(
                    "aliyunId", locale.msg("private.account.delete.validation.aliyunId.deleteSuperUser"));
            }
        }

        // ?????????????????????????????????
        String triggerType = UserCallbackTypeEnum.USER_DELETED.toString();
        List<UserCallbackDO> callbacks = userCallbackMapper.selectByTriggerType(triggerType);
        if (null != callbacks) {
            Map<String, String> requestData = new HashMap<>();
            requestData.put("pk", user.getAliyunPk());
            requestData.put("aliyunId", aliyunId);
            String requestBodyStr;
            try {
                requestBodyStr = jsonMapper.writeValueAsString(requestData);
            } catch (JsonProcessingException e) {
                throw new PrivateInternalError(String.format("Cannot build request data, requestData=%s",
                    gson.toJson(requestData)));
            }
            for (UserCallbackDO callback : callbacks) {
                String url = callback.getUrl();
                Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON_MEDIA_TYPE, requestBodyStr))
                    .build();
                Response resp;
                String responseString = "";
                try {
                    resp = httpClient.newCall(req).execute();
                    ResponseBody responseBody = resp.body();
                    if (null != responseBody) {
                        responseString = responseBody.string();
                    }
                    JsonNode result = jsonMapper.readTree(responseString);
                    Integer code = result.get("code").asInt(-1);
                    if (!code.equals(200)) {
                        throw new PrivateValidationError("aliyunId", result.get("message").asText());
                    }
                } catch (PrivateValidationError e) {
                    throw e;
                } catch (Exception e) {
                    AuthProxyThirdPartyError se = new AuthProxyThirdPartyError(Constants.THIRD_PARTY_CALLBACK,
                        String.format("Callback %s failed, requestBody=%s, response=%s, exception=%s",
                            url, requestBodyStr, responseString, ExceptionUtils.getStackTrace(e)));
                    se.initCause(e);
                    throw se;
                }
                log.info("Account {} delete process, post callback url {}, response={}", aliyunId, url, responseString);
            }
        }

        // ??????????????????????????????????????? -1, ??????????????? Access Keys ?????????
        String aliyunPk = user.getAliyunPk();
        String randomPassword = TEMP_PASSWORD_PREFIX + passwordUtil.generate(10);
        Date now = new Date();
        throw new RuntimeException("not support");
        //try {
        //    aasClient.updatePassword(aliyunPk, randomPassword);
        //    aasClient.deleteAccessKey(aliyunPk);
        //} catch (ClientException e) {
        //    AuthProxyThirdPartyError se = new AuthProxyThirdPartyError(Constants.THIRD_PARTY_AAS,
        //        "UpdatePassword/DeleteAccessKey failed: " + e.getMessage());
        //    se.initCause(e);
        //    throw se;
        //}
        //user.setStatus(-1);
        //user.setGmtModified(now);
        //user.setAccessKeyId("");
        //user.setAccessKeySecret("");
        //teslaUserService.update(user);
    }

    /**
     * ??????????????????
     */
    @Override
    public PrivateAccountValidationResult validation(PrivateAccountValidationParam param)
        throws PrivateValidationError {
        String validation = param.getValidation();
        if (!validation.equals(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION_PASSWORD_MOBILE) &&
            !validation.equals(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION_PASSWORD)) {
            throw new PrivateValidationError(
                "validation", locale.msg("private.account.validation.validation.invalid")
            );
        }

        if (validation.equals(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION_PASSWORD_MOBILE)) {
            // ??????????????????????????????????????????????????????????????????????????????????????????
            UserDO superUser = teslaUserService.getUserByLoginName(authProperties.getAasSuperUser());
            if (superUser == null) {
                throw new PrivateValidationError("validation",
                    locale.msg("private.account.validation.cannot_find_superuser"));
            }
            if (StringUtil.isEmpty(superUser.getPhone())) {
                throw new PrivateValidationError("validation",
                    locale.msg("private.account.validation.superuser_no_phone", authProperties.getAasSuperUser()));
            }

            // ????????????????????????????????????
            ConfigDO smsEndpoint = configMapper.getByName(Constants.CONFIG_PRIVATE_SMS_ENDPOINT);
            ConfigDO smsToken = configMapper.getByName(Constants.CONFIG_PRIVATE_SMS_TOKEN);
            if (smsEndpoint == null || smsToken == null || StringUtil.isEmpty(smsEndpoint.getValue()) ||
                StringUtil.isEmpty(smsToken.getValue())) {
                throw new PrivateValidationError("validation",
                    locale.msg("private.account.validation.sms_gateway_not_set"));
            }
        }

        // ?????????????????????
        ConfigDO configDo = ConfigDO.builder()
            .name(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION)
            .value(validation)
            .build();
        configMapper.save(configDo);

        PrivateAccountValidationResult result = new PrivateAccountValidationResult();
        result.setValidation(validation);
        return result;
    }

    /**
     * ??????????????????
     */
    @Override
    public PrivateAccountLoginOptionResult getLoginOption(String aliyunId) {
        PrivateAccountLoginOptionResult result = new PrivateAccountLoginOptionResult();
        UserDO user = teslaUserService.getUserByLoginName(aliyunId);

        // ???????????????????????????????????????????????????????????????????????????????????????
        if (null == user) {
            result.setValidation(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION_PASSWORD);
            return result;
        }

        // ?????????????????????????????????????????????????????????????????????
        ConfigDO config = configMapper.getByName(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION);
        if (config == null) {
            log.warn("Cannot getOamClient private account validation from config table, use password default");
            result.setValidation(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION_PASSWORD);
        } else {
            result.setValidation(config.getValue());
        }
        return result;
    }

    /**
     * ?????????????????????
     */
    @Override
    public PrivateAccountLoginSmsResult loginSms(PrivateAccountLoginSmsParam param, HttpSession session)
        throws PrivateValidationError, AuthProxyThirdPartyError {
        String aliyunId = param.getAliyunId();
        String code = Integer.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000)).toString();
        Long now = LocalDateTime.now().atZone(ZoneId.systemDefault()).toEpochSecond();
        Long expiresAt = now + authProperties.getLoginSmsExpireSeconds();

        UserDO user = teslaUserService.getUserByLoginName(aliyunId);
        if (null == user) {
            throw new PrivateValidationError(
                "aliyunId", locale.msg("private.account.login.sms.validation.aliyunId.notExist"));
        }
        if (null == user.getPhone() || user.getPhone().length() == 0) {
            throw new PrivateValidationError(
                "aliyunId", locale.msg("private.account.login.sms.validation.aliyunId.emptyPhone"));
        }

        // ?????? Session ?????????????????????
        session.setAttribute("loginAliyunId", aliyunId);
        session.setAttribute("loginCode", code);
        session.setAttribute("loginExpiresAt", expiresAt.toString());
        log.info("Generate login code {} for aliyunId {}, expires at {}", code, aliyunId, expiresAt);
        smsService.sendMessage(user.getPhone(), aliyunId, code,
            locale.msg("private.account.login.sms.template", aliyunId, code));

        PrivateAccountLoginSmsResult result = new PrivateAccountLoginSmsResult();
        result.setAliyunId(aliyunId);
        result.setPhone(user.getPhone());
        return result;
    }

    /**
     * ??????
     */
    @Override
    public AasLoginResult login(PrivateAccountLoginParam param, HttpSession session)
        throws PrivateValidationError, AuthProxyThirdPartyError {
        // ????????????
        String aliyunId = param.getAliyunId();
        String password = param.getPassword();
        String smsCode = param.getSmsCode();

        // ?????????????????????
        loginSmsCodeValidation(aliyunId, smsCode, session);

        // ????????????????????????????????????????????????????????????????????????????????????
        AasLoginResult loginResult = aasClient.login(aliyunId, password);
        switch (loginResult.getStatus()) {
            case SUCCESS:
                break;
            case USER_TEMP_LOCKED:
                try {
                    lock(aliyunId, true);
                } catch (PrivateValidationError ignored) {
                }
                return loginResult;
            case PASSWORD_ERROR:
                //Long count = redisTemplate.opsForValue().increment("authproxy:login:wrongPasswordCount:" +
                // aliyunId, 1L);
                //if (count >= 5) {
                //    try {
                //        lock(aliyunId, true);
                //    } catch (PrivateValidationError ignored) {}
                //    redisTemplate.opsForValue().set("authproxy:login:wrongPasswordCount:" + aliyunId, 0L);
                //    loginResult.setStatus(AasLoginStatusEnum.USER_LOCKED);
                //}
                return loginResult;
            default:
                return loginResult;
        }

        // ?????????????????????????????????????????????????????????
        AasUserInfo aasUserInfo;
        try {
            aasUserInfo = aasClient.loadUserInfo(loginResult.getLoginAliyunIdTicket());
        } catch (PrivateAasVerifyFailed e) {
            loginResult.setStatus(AasLoginStatusEnum.AAS_FAULT);
            return loginResult;
        } catch (AuthProxyThirdPartyError e) {
            loginResult.setStatus(AasLoginStatusEnum.UNKNOWN);
            loginResult.setMessage(e.getMessage());
            return loginResult;
        }
        String aliyunPk = aasUserInfo.getAliyunPK();

        // ???????????? Access Key ??? Access Secret
        //ListAccessKeysForAccountResponse.AccessKey accessKey = getUserAccessKeys(aliyunPk);
        //String accessKeyId = accessKey.getAccessKeyId();
        //String accessKeySecret = accessKey.getAccessKeySecret();
        //log.info("Get accessKey successful, accessKeyId={}, accessKeySecret={}, aliyunPk={}",
        //    accessKeyId, accessKeySecret, aliyunPk);

        // ????????????????????????????????????
        Date now = new Date();
        UserDO userDo = teslaUserService.getUserByLoginName(aasUserInfo.getAliyunID());
        if (null == userDo || userDo.getStatus() == -1) {
            if (null != userDo) {
                userMapper.deleteByPrimaryKey(userDo.getId());
            }
            //userDo = aasUserInfo.toTeslaUserDo(accessKeyId, accessKeySecret);
            try {
                teslaUserService.save(userDo);
                insertPasswordChangeOpLog(userDo.getLoginName());
                log.info("AAS user info has inserted into db, user={}", TeslaGsonUtil.toJson(userDo));
            } catch (Exception e) {
                if (e.getCause() instanceof MySQLIntegrityConstraintViolationException) {
                    log.info("Repeat AAS user info in db, skip, user={}", TeslaGsonUtil.toJson(userDo));
                } else {
                    log.warn("Insert AAS user info into db failed, user={}, message={}",
                        TeslaGsonUtil.toJson(userDo), e.getMessage());
                    loginResult.setStatus(AasLoginStatusEnum.UNKNOWN);
                    loginResult.setMessage(e.getMessage());
                    return loginResult;
                }
            }
        } else if (userDo.getIsLocked().intValue() == 1) {
            loginResult.setStatus(AasLoginStatusEnum.USER_LOCKED);
            return loginResult;
        } else {
            userDo.setGmtModified(now);
            userDo.setLastLoginTime(now);
            //userDo.setAccessKeyId(accessKeyId);
            //userDo.setAccessKeySecret(accessKeySecret);
            teslaUserService.update(userDo);
        }
        loginResult.setUserInfo(userDo);
        return loginResult;
    }

    /**
     * ???????????? aliyunId ?????????????????????????????????
     *
     * @param aliyunId ????????? ID
     * @return ????????????????????????
     */
    @Override
    public LocalDateTime getUserNextPasswordChangeDate(String aliyunId) {
        OplogDO op = oplogMapper.getLastByUserAndAction(aliyunId, Constants.OP_PASSWORD_CHANGE);
        if (null == op) {
            log.warn("No password change op log found for aliyunId {}, created now", aliyunId);
            op = insertPasswordChangeOpLog(aliyunId);
        }
        LocalDateTime createDate = op.getGmtCreate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        return createDate.plusMonths(authProperties.getAasPasswordExpireMonths());
    }
    //
    ///**
    // * ????????????: ?????? aliyunPk ??????????????? Access Key ??????
    // *
    // * @param aliyunPk Aliyun PK
    // * @return AccessKey ??????
    // * @throws AuthProxyThirdPartyError AAS ?????????????????????
    // * @throws PrivateValidationError   ??????????????? Access Key ???????????????
    // */
    //private ListAccessKeysForAccountResponse.AccessKey getUserAccessKeys(String aliyunPk)
    //    throws AuthProxyThirdPartyError, PrivateValidationError {
    //    List<ListAccessKeysForAccountResponse.AccessKey> accessKeyList = aasClient.getUserAccessKeys(aliyunPk);
    //    if (accessKeyList.size() == 0) {
    //        throw new PrivateValidationError(
    //            "nonExistField", locale.msg("private.account.login.validation.nonExistField.accessKeyNotExist")
    //        );
    //    } else if (accessKeyList.size() > 1) {
    //        log.warn("Multiple access key found in account {}, select first", aliyunPk);
    //    }
    //    return accessKeyList.get(0);
    //}

    /**
     * ????????????????????????????????????????????????
     *
     * @param aliyunId ????????? ID
     * @param smsCode  ???????????????
     * @param session  HttpSession
     * @throws PrivateValidationError ????????????????????????
     */
    private void loginSmsCodeValidation(String aliyunId, String smsCode, HttpSession session)
        throws PrivateValidationError {
        String validationMethod = getLoginOption(aliyunId).getValidation();
        if (!validationMethod.equals(Constants.CONFIG_PRIVATE_ACCOUNT_VALIDATION_PASSWORD_MOBILE)) {
            return;
        }
        if (smsCode.length() == 0) {
            throw new PrivateValidationError(
                "smsCode", locale.msg("private.account.login.validation.smsCode.required"));
        }
        Object sessionAliyunIdObject = session.getAttribute("loginAliyunId");
        Object sessionLoginCodeObject = session.getAttribute("loginCode");
        Object sessionLoginExpiresAt = session.getAttribute("loginExpiresAt");
        if (null == sessionAliyunIdObject || null == sessionLoginCodeObject || null == sessionLoginExpiresAt) {
            throw new PrivateValidationError(
                "smsCode", locale.msg("private.account.login.validation.nonExistField.invalidLoginCode"));
        }
        Long now = LocalDateTime.now().atZone(ZoneId.systemDefault()).toEpochSecond();
        String sessionAliyunId = sessionAliyunIdObject.toString();
        String sessionLoginCode = sessionLoginCodeObject.toString();
        Long sessionExpiresAt = Long.valueOf(sessionLoginExpiresAt.toString());
        if (now > sessionExpiresAt) {
            throw new PrivateValidationError(
                "smsCode", locale.msg("private.account.login.validation.nonExistField.invalidLoginCode"));
        }
        if (!aliyunId.equals(sessionAliyunId)) {
            throw new PrivateValidationError(
                "smsCode", locale.msg("private.account.login.validation.nonExistField.matchLoginCodeFailed"));
        }
        if (!smsCode.equals(sessionLoginCode)) {
            throw new PrivateValidationError(
                "smsCode", locale.msg("private.account.login.validation.nonExistField.wrongLoginCode"));
        }
    }

    /**
     * ????????????????????? OP log ??????
     *
     * @param aliyunId aliyun Id
     */
    private OplogDO insertPasswordChangeOpLog(String aliyunId) {
        Date now = new Date();
        OplogDO op = new OplogDO();
        op.setOpUser(aliyunId);
        op.setOpAction(Constants.OP_PASSWORD_CHANGE);
        op.setOpResult(1);
        op.setOpTime(now);
        op.setGmtCreate(now);
        op.setGmtModified(now);
        oplogMapper.insert(op);
        return op;
    }

    /**
     * ?????????????????? AAS ????????????????????????????????????
     */
    @Override
    public void checkLoginStatus(AasLoginResult result) throws PrivateValidationError {
        String message;
        switch (result.getStatus()) {
            case SUCCESS:
                break;
            case USER_NOT_EXIST:
                message = locale.msg("private.account.login.validation.aliyunId.notExist");
                throw new PrivateValidationError("aliyunId", message);
            case PASSWORD_ERROR:
                message = locale.msg("private.account.login.validation.password.error");
                throw new PrivateValidationError("password", message);
            case USER_TEMP_LOCKED:
                message = locale.msg("private.account.login.validation.nonExistField.tempLocked");
                throw new PrivateValidationError("nonExistField", message);
            case USER_LOCKED:
                message = locale.msg("private.account.login.validation.nonExistField.locked");
                throw new PrivateValidationError("nonExistField", message);
            case AAS_FAULT:
                message = locale.msg("private.account.login.validation.nonExistField.aasFault");
                throw new PrivateValidationError("nonExistField", message);
            case UNKNOWN:
                message = locale.msg("private.account.login.validation.nonExistField.unknown", result.getMessage());
                log.warn("invalid aas login response, loginResult={}", result.toString());
                throw new PrivateValidationError("nonExistField", message);
        }
    }

    /**
     * ???????????????????????????????????????????????????????????????
     */
    private String validatePhoneNumber(String phone, HttpServletRequest request) throws PrivateValidationError {
        for (Character c : phone.toCharArray()) {
            if (!" +()0123456789".contains(c.toString())) {
                throw new PrivateValidationError("phone", locale.msg("private.validation.phone.invalid"));
            }
        }
        return phone;
        //Locale requestLocale = localeResolver.resolveLocale(request);
        //PhoneNumber phoneNumber;
        //try {
        //    phoneNumber = phoneNumberUtil.parse(phone, requestLocale.getCountry());
        //} catch (NumberParseException e) {
        //    throw new PrivateValidationError("phone", locale.msg("private.validation.phone.invalid"));
        //}
        //if (!phoneNumberUtil.isValidNumber(phoneNumber)) {
        //    throw new PrivateValidationError("phone", locale.msg("private.validation.phone.invalid"));
        //}
        //return phoneNumberUtil.format(phoneNumber, PhoneNumberFormat.E164);
    }

    /**
     * ?????????????????????????????????
     *
     * @param password ????????????
     * @throws PrivateValidationError ??????????????????????????????
     */
    private void validatePassword(String password) throws PrivateValidationError {
        if (password.length() < 10 || password.length() > 20) {
            throw new PrivateValidationError("password", locale.msg("private.aas.updatePassword.wrongLength"));
        }
        Integer hasUpper = 0;
        Integer hasLower = 0;
        Integer hasDigit = 0;
        Integer hasSymbol = 0;
        if (!password.toLowerCase().equals(password)) {
            hasUpper = 1;
        }
        if (!password.toUpperCase().equals(password)) {
            hasLower = 1;
        }
        for (char c : password.toCharArray()) {
            if (Character.isDigit(c)) {
                hasDigit = 1;
            } else if (Character.isUpperCase(c)) {
                hasUpper = 1;
            } else if (Character.isLowerCase(c)) {
                hasLower = 1;
            } else {
                hasSymbol = 1;
            }
        }
        if (hasUpper + hasLower + hasDigit + hasSymbol < 3) {
            throw new PrivateValidationError("password", locale.msg("private.aas.updatePassword.malformed"));
        }
    }

}

/**
 * ???????????? aliyunPk ??????
 *
 * @author yaoxing.gyx@alibaba-inc.com
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class GetAliyunPk {

    private String kp;

    private String aliyunId;
}
