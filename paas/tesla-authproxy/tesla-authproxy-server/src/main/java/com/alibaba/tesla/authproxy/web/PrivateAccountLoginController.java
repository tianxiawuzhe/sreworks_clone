package com.alibaba.tesla.authproxy.web;

import com.alibaba.tesla.authproxy.AuthProperties;
import com.alibaba.tesla.authproxy.Constants;
import com.alibaba.tesla.authproxy.component.cookie.ResponseCookie;
import com.alibaba.tesla.authproxy.constants.AuthProxyConstants;
import com.alibaba.tesla.authproxy.lib.exceptions.AuthProxyThirdPartyError;
import com.alibaba.tesla.authproxy.lib.exceptions.PrivateValidationError;
import com.alibaba.tesla.authproxy.model.UserDO;
import com.alibaba.tesla.authproxy.outbound.aas.AasLoginResult;
import com.alibaba.tesla.authproxy.service.PrivateAccountService;
import com.alibaba.tesla.authproxy.service.TeslaUserService;
import com.alibaba.tesla.authproxy.util.CookieUtil;
import com.alibaba.tesla.authproxy.util.TeslaJwtUtil;
import com.alibaba.tesla.authproxy.util.UserUtil;
import com.alibaba.tesla.authproxy.util.audit.*;
import com.alibaba.tesla.authproxy.web.common.PrivateBaseController;
import com.alibaba.tesla.authproxy.web.common.PrivateResultBuilder;
import com.alibaba.tesla.authproxy.web.input.PrivateAccountLoginOptionParam;
import com.alibaba.tesla.authproxy.web.input.PrivateAccountLoginParam;
import com.alibaba.tesla.authproxy.web.input.PrivateAccountLoginSmsParam;
import com.alibaba.tesla.authproxy.web.output.PrivateAccountLoginOptionResult;
import com.alibaba.tesla.authproxy.web.output.PrivateAccountLoginResult;
import com.alibaba.tesla.authproxy.web.output.PrivateAccountLoginSmsResult;
import com.alibaba.tesla.common.utils.TeslaResult;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * ????????? - ???????????? ??????
 *
 * @author yaoxing.gyx@alibaba-inc.com
 */
@RestController
@RequestMapping("auth/private/account/login")
@Slf4j
public class PrivateAccountLoginController extends PrivateBaseController {

    @Autowired
    private PrivateAccountService accountService;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private TeslaUserService teslaUserService;

    @Autowired
    private AuditUtil auditUtil;

    /**
     * ?????????????????? API
     */
    @RequestMapping(value = "option", method = RequestMethod.GET)
    @ResponseBody
    public TeslaResult option(@Valid @ModelAttribute PrivateAccountLoginOptionParam param,
        BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return buildValidationResult(bindingResult);
        }
        param.cleanSelf();

        PrivateAccountLoginOptionResult data = accountService.getLoginOption(param.getAliyunId());
        return PrivateResultBuilder.buildExtSuccessResult(data);
    }

    /**
     * ????????????????????? API
     */
    @RequestMapping(value = "sms", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TeslaResult loginSms(@Valid @RequestBody PrivateAccountLoginSmsParam param, BindingResult bindingResult,
        HttpServletRequest request)
        throws PrivateValidationError, AuthProxyThirdPartyError {
        if (bindingResult.hasErrors()) {
            return buildValidationResult(bindingResult);
        }
        param.cleanSelf();

        PrivateAccountLoginSmsResult result = accountService.loginSms(param, request.getSession());
        return PrivateResultBuilder.buildExtSuccessResult(result);
    }

    /**
     * ?????? API
     */
    @RequestMapping(value = "", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TeslaResult login(@Valid @RequestBody PrivateAccountLoginParam param, BindingResult bindingResult,
        HttpServletRequest request, HttpServletResponse response)
        throws PrivateValidationError, AuthProxyThirdPartyError {
        if (bindingResult.hasErrors()) {
            return buildValidationResult(bindingResult);
        }
        param.cleanSelf();

        // ?????????????????????????????????
        AasLoginResult loginResult = accountService.login(param, request.getSession());
        log.info("aas login response, aliyunId={}, loginResult={}", param.getAliyunId(), loginResult.toString());
        accountService.checkLoginStatus(loginResult);

        // ?????????????????? AAS ????????? Cookie ???????????? response ???
        List<Cookie> cookies = loginResult.getCookies();
        for (Cookie cookie : cookies) {
            javax.servlet.http.Cookie newCookie = new javax.servlet.http.Cookie(cookie.name(), cookie.value());
            newCookie.setPath("/");
            newCookie.setDomain(authProperties.getCookieDomain());
            response.addCookie(newCookie);
        }

        //??????tesla token cookie
        UserDO userDo = loginResult.getUserInfo();
        String teslaToken = TeslaJwtUtil.create(userDo.getEmpId(), userDo.getLoginName(),
            Objects.toString(userDo.getBucId()), userDo.getEmail(),
            UserUtil.getUserId(userDo), userDo.getNickName(), userDo.getAliyunPk(), TeslaJwtUtil.JWT_TOKEN_TIMEOUT,
            authProperties.getOauth2JwtSecret());
        if(Objects.equals(authProperties.getNetworkProtocol(),"https")){
            ResponseCookie responseCookie = ResponseCookie.builder().name(AuthProxyConstants.COOKIE_SSO_LOGIN_TOKEN).value(teslaToken)
                .maxAge(Duration.ofSeconds(24 * 60 * 60))
                .domain(authProperties.getCookieDomain())
                .sameSite("None")
                .secure(true)
                .path("/")
                .build();
            response.addHeader("Set-Cookie", responseCookie.toString());
        }else{
            CookieUtil.setCookie(response, AuthProxyConstants.COOKIE_SSO_LOGIN_TOKEN, teslaToken, 0);
        }


        // ?????????????????????
        String lang = param.getLang();
        String[] langs = lang.split("_");
        if (langs.length == 2) {
            CookieUtil.setCookie(response, Constants.ALIYUN_COOKIE_LANG, langs[0], 0, authProperties.getCookieDomain());
            CookieUtil.setCookie(response, Constants.ALIYUN_COOKIE_TERRITORY, langs[1], 0,
                authProperties.getCookieDomain());
            userDo = loginResult.getUserInfo();
            teslaUserService.changeLanguage(userDo, lang);
        } else {
            log.error("Cannot write user language into cookie/db, lang split length not 2, lang={}", lang);
        }

        // ??????????????????
        LocalDateTime now = LocalDateTime.now();
        PrivateAccountLoginResult result = new PrivateAccountLoginResult();
        result.setAliyunId(loginResult.getLoginAliyunId());
        result.setIsFirstLogin(loginResult.getUserInfo().getIsFirstLogin() != 0 ? Boolean.TRUE : Boolean.FALSE);
        LocalDateTime passwordChangeDateTime = accountService.getUserNextPasswordChangeDate(
            loginResult.getLoginAliyunId());
        result.setPasswordChangeTime(Date.from(passwordChangeDateTime.atZone(ZoneId.systemDefault()).toInstant()));
        result.setPasswordChangeRestDays((int)ChronoUnit.DAYS.between(now, passwordChangeDateTime));
        result.setPasswordChangeNotify(passwordChangeDateTime.minusMonths(1).isBefore(now));
        userDo = new UserDO();
        userDo.setLoginName(loginResult.getLoginAliyunId());
        auditUtil.info(userDo, AuditTargetEnum.DATA, AuditActionEnum.SELECT, AuditOutcomeEnum.SUCCESS,
            "authproxy - login user", AuditReasonEnum.AUTHORIZED);
        return PrivateResultBuilder.buildExtSuccessResult(result);
    }

}
