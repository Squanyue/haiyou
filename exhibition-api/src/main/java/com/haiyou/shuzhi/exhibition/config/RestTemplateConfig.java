package com.haiyou.shuzhi.exhibition.config;

import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.security.cert.X509Certificate;

/**
 * RestTemplate 配置
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Configuration
public class RestTemplateConfig {

    /**
     * 通用 RestTemplate（飞书等公网 HTTPS）。
     * 部分内网环境存在 SSL 中间人/证书链不全，测试环境跳过证书校验。
     */
    @Bean
    public RestTemplate restTemplate() throws Exception {
        return buildTrustAllRestTemplate();
    }

    /**
     * EAD 专用 RestTemplate。
     * 内网证书域名常与访问域名不一致（SAN 不匹配），测试环境跳过证书校验。
     */
    @Bean
    @Qualifier("eadRestTemplate")
    public RestTemplate eadRestTemplate() throws Exception {
        return buildTrustAllRestTemplate();
    }

    private RestTemplate buildTrustAllRestTemplate() throws Exception {
        TrustStrategy acceptingTrustStrategy = new TrustStrategy() {
            @Override
            public boolean isTrusted(X509Certificate[] chain, String authType) {
                return true;
            }
        };
        SSLContext sslContext = SSLContexts.custom()
                .loadTrustMaterial(null, acceptingTrustStrategy)
                .build();
        SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(
                sslContext, NoopHostnameVerifier.INSTANCE);
        CloseableHttpClient httpClient = HttpClients.custom()
                .setSSLSocketFactory(socketFactory)
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(15000);
        // 大附件分片上传可能较久
        factory.setReadTimeout(300000);
        return new RestTemplate(factory);
    }
}
