package com.haiyou.shuzhi.exhibition.service.impl;

import com.haiyou.shuzhi.exhibition.config.FeishuProperties;
import com.haiyou.shuzhi.exhibition.dto.FeishuAppAccessTokenRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuAppAccessTokenVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuTokenResponse;
import com.haiyou.shuzhi.exhibition.service.FeishuAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 飞书认证服务实现
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuAuthServiceImpl implements FeishuAuthService {

    private static final String APP_ACCESS_TOKEN_PATH = "/open-apis/auth/v3/app_access_token/internal";

    private final RestTemplate restTemplate;
    private final FeishuProperties feishuProperties;

    @Override
    public FeishuAppAccessTokenVO getAppAccessToken(FeishuAppAccessTokenRequest request) {
        String appId = resolveAppId(request);
        String appSecret = resolveAppSecret(request);
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            throw new IllegalArgumentException("appId 和 appSecret 不能为空，请在请求体中传入或在 application.yml 中配置 feishu.app-id / feishu.app-secret");
        }

        String url = feishuProperties.getBaseUrl() + APP_ACCESS_TOKEN_PATH;
        Map<String, String> body = new HashMap<String, String>(4);
        body.put("app_id", appId);
        body.put("app_secret", appSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT_CHARSET, StandardCharsets.UTF_8.name());
        HttpEntity<Map<String, String>> entity = new HttpEntity<Map<String, String>>(body, headers);

        FeishuTokenResponse response;
        try {
            log.info("调用飞书获取 app_access_token, url={}, appId={}", url, appId);
            ResponseEntity<FeishuTokenResponse> responseEntity =
                    restTemplate.postForEntity(url, entity, FeishuTokenResponse.class);
            response = responseEntity.getBody();
        } catch (RestClientException ex) {
            log.error("调用飞书获取 app_access_token 失败", ex);
            throw new IllegalStateException("调用飞书接口失败: " + ex.getMessage(), ex);
        }

        if (response == null) {
            throw new IllegalStateException("飞书接口返回为空");
        }
        if (response.getCode() == null || response.getCode() != 0) {
            String msg = StringUtils.hasText(response.getMsg()) ? response.getMsg() : "未知错误";
            log.warn("飞书获取 app_access_token 失败, code={}, msg={}", response.getCode(), msg);
            throw new IllegalStateException("飞书返回错误, code=" + response.getCode() + ", msg=" + msg);
        }

        FeishuAppAccessTokenVO vo = new FeishuAppAccessTokenVO();
        vo.setAppAccessToken(response.getAppAccessToken());
        vo.setTenantAccessToken(response.getTenantAccessToken());
        vo.setExpire(response.getExpire());
        return vo;
    }

    @Override
    public String getTenantAccessToken() {
        FeishuAppAccessTokenVO tokenVO = getAppAccessToken(null);
        if (tokenVO == null || !StringUtils.hasText(tokenVO.getTenantAccessToken())) {
            throw new IllegalStateException("未能获取到 tenant_access_token");
        }
        return tokenVO.getTenantAccessToken();
    }

    private String resolveAppId(FeishuAppAccessTokenRequest request) {
        if (request != null && StringUtils.hasText(request.getAppId())) {
            return request.getAppId();
        }
        return feishuProperties.getAppId();
    }

    private String resolveAppSecret(FeishuAppAccessTokenRequest request) {
        if (request != null && StringUtils.hasText(request.getAppSecret())) {
            return request.getAppSecret();
        }
        return feishuProperties.getAppSecret();
    }
}
