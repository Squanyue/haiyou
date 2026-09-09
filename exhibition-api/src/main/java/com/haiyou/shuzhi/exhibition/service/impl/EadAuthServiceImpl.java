package com.haiyou.shuzhi.exhibition.service.impl;

import com.haiyou.shuzhi.exhibition.config.EadProperties;
import com.haiyou.shuzhi.exhibition.dto.EadTokenVO;
import com.haiyou.shuzhi.exhibition.service.EadAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * EAD 认证服务实现
 *
 * @author exhibition
 * @date 2026-07-29
 */
@Slf4j
@Service
public class EadAuthServiceImpl implements EadAuthService {

    private final RestTemplate eadRestTemplate;
    private final EadProperties eadProperties;

    public EadAuthServiceImpl(@Qualifier("eadRestTemplate") RestTemplate eadRestTemplate,
                              EadProperties eadProperties) {
        this.eadRestTemplate = eadRestTemplate;
        this.eadProperties = eadProperties;
    }

    @Override
    public EadTokenVO getToken(String userAccount) {
        if (!StringUtils.hasText(userAccount)) {
            throw new IllegalArgumentException("userAccount 不能为空");
        }
        if (!StringUtils.hasText(eadProperties.getAppId()) || !StringUtils.hasText(eadProperties.getAppSecret())) {
            throw new IllegalArgumentException("请先在 application.yml 配置 ead.app-id / ead.app-secret");
        }
        if (!StringUtils.hasText(eadProperties.getTokenUrl())) {
            throw new IllegalArgumentException("请先在 application.yml 配置 ead.token-url");
        }

        Map<String, String> body = new HashMap<String, String>(4);
        body.put("appId", eadProperties.getAppId());
        body.put("appSecret", eadProperties.getAppSecret());
        body.put("userAccount", userAccount);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> entity = new HttpEntity<Map<String, String>>(body, headers);

        log.info("调用 EAD 获取令牌, url={}, appId={}, userAccount={}",
                eadProperties.getTokenUrl(), eadProperties.getAppId(), userAccount);

        try {
            ResponseEntity<EadTokenVO> responseEntity =
                    eadRestTemplate.postForEntity(eadProperties.getTokenUrl(), entity, EadTokenVO.class);
            EadTokenVO tokenVO = responseEntity.getBody();
            if (tokenVO == null || !StringUtils.hasText(tokenVO.getAccessToken())) {
                throw new IllegalStateException("EAD 返回令牌为空");
            }
            log.info("EAD 获取令牌成功, userAccount={}, expiresIn={}", userAccount, tokenVO.getExpiresIn());
            return tokenVO;
        } catch (RestClientException ex) {
            log.error("调用 EAD 获取令牌失败, userAccount={}", userAccount, ex);
            throw new IllegalStateException("调用 EAD 获取令牌失败: " + ex.getMessage(), ex);
        }
    }
}
