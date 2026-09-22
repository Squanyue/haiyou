package com.haiyou.shuzhi.exhibition.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haiyou.shuzhi.exhibition.common.EadConstants;
import com.haiyou.shuzhi.exhibition.config.EadProperties;
import com.haiyou.shuzhi.exhibition.dto.EadTokenVO;
import com.haiyou.shuzhi.exhibition.service.EadAuthService;
import com.haiyou.shuzhi.exhibition.service.EadProcessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EAD 流程服务实现
 *
 * @author exhibition
 * @date 2026-07-30
 */
@Slf4j
@Service
public class EadProcessServiceImpl implements EadProcessService {

    private static final String EAD_ICON_FIELD = "filesIcon";
    private static final String EAD_MATERIALS_FIELD = "filesMaterials";
    private static final String EAD_ATTACHMENT_FIELD = "filesAttachment";

    private final RestTemplate eadRestTemplate;
    private final EadProperties eadProperties;
    private final EadAuthService eadAuthService;
    private final ObjectMapper objectMapper;

    public EadProcessServiceImpl(@Qualifier("eadRestTemplate") RestTemplate eadRestTemplate,
                                 EadProperties eadProperties,
                                 EadAuthService eadAuthService,
                                 ObjectMapper objectMapper) {
        this.eadRestTemplate = eadRestTemplate;
        this.eadProperties = eadProperties;
        this.eadAuthService = eadAuthService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Object startProcess(String userAccount, String formJson, MultipartFile[] files) {
        Map<String, MultipartFile[]> filesByField = new LinkedHashMap<String, MultipartFile[]>();
        if (files != null && files.length > 0) {
            filesByField.put(EAD_ATTACHMENT_FIELD, files);
        }
        return startProcess(userAccount, formJson, filesByField);
    }

    @Override
    public Object startProcess(String userAccount, String formJson, Map<String, MultipartFile[]> filesByField) {
        if (!StringUtils.hasText(userAccount)) {
            throw new IllegalArgumentException("userAccount 不能为空");
        }
        if (!StringUtils.hasText(formJson)) {
            throw new IllegalArgumentException("formJson 不能为空");
        }
        if (!StringUtils.hasText(eadProperties.getProcessStartUrl())) {
            throw new IllegalArgumentException("请配置 ead.process-start-url");
        }

        EadTokenVO tokenVO = eadAuthService.getToken(userAccount);
        String accessToken = tokenVO.getAccessToken();
        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalStateException("获取 EAD access_token 失败");
        }

        String jsonFieldName = EadConstants.PROCESS_START_JSON_FIELD;
        if (jsonFieldName == null) {
            jsonFieldName = "";
        }

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> jsonPart = new HttpEntity<String>(formJson, jsonHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<String, Object>();
        body.add(jsonFieldName, jsonPart);

        int fileCount = appendFiles(body, filesByField);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        HttpEntity<MultiValueMap<String, Object>> requestEntity =
                new HttpEntity<MultiValueMap<String, Object>>(body, headers);

        log.info("调用 EAD 发起流程, url={}, userAccount={}, jsonFieldName={}, fileFields={}, fileCount={}",
                eadProperties.getProcessStartUrl(),
                userAccount,
                jsonFieldName,
                filesByField == null ? "[]" : filesByField.keySet(),
                fileCount);
        log.info("EAD 发起流程 formJson={}", formJson);

        try {
            ResponseEntity<String> responseEntity = eadRestTemplate.postForEntity(
                    eadProperties.getProcessStartUrl(), requestEntity, String.class);
            String responseBody = responseEntity.getBody();
            log.info("EAD 发起流程响应: {}", responseBody);
            return parseResponse(responseBody);
        } catch (RestClientException ex) {
            log.error("调用 EAD 发起流程失败", ex);
            throw new IllegalStateException("调用 EAD 发起流程失败: " + ex.getMessage(), ex);
        }
    }

    private int appendFiles(MultiValueMap<String, Object> body, Map<String, MultipartFile[]> filesByField) {
        if (filesByField == null || filesByField.isEmpty()) {
            return 0;
        }
        int count = 0;
        String[] fieldNames = {EAD_ICON_FIELD, EAD_MATERIALS_FIELD, EAD_ATTACHMENT_FIELD};
        for (String fieldName : fieldNames) {
            MultipartFile[] files = filesByField.get(fieldName);
            if (files == null) {
                continue;
            }
            int validFileCount = 0;
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    validFileCount++;
                }
            }
            boolean indexedField = validFileCount > 1;
            int fieldIndex = 0;
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String multipartFieldName = indexedField
                        ? fieldName + "[" + fieldIndex++ + "]"
                        : fieldName;
                addFilePart(body, multipartFieldName, file);
                count++;
                log.info("EAD 附件转发, fileName={}, contentType={}, fieldName={}",
                        file.getOriginalFilename(), file.getContentType(), multipartFieldName);
            }
        }
        return count;
    }

    private void addFilePart(MultiValueMap<String, Object> body,
                             String fieldName,
                             MultipartFile file) {
        String filename = file.getOriginalFilename();
        final String safeName = StringUtils.hasText(filename) ? filename : "unnamed.bin";
        ByteArrayResource fileResource = new ByteArrayResource(readBytes(file)) {
            @Override
            public String getFilename() {
                return safeName;
            }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        MediaType contentType = MediaType.APPLICATION_OCTET_STREAM;
        if (StringUtils.hasText(file.getContentType())) {
            try {
                contentType = MediaType.parseMediaType(file.getContentType());
            } catch (IllegalArgumentException ex) {
                log.warn("附件 Content-Type 无效，使用 application/octet-stream, fileName={}, contentType={}",
                        safeName, file.getContentType());
            }
        }
        fileHeaders.setContentType(contentType);
        fileHeaders.setContentDisposition(ContentDisposition.builder("form-data")
                .name(fieldName)
                .filename(safeName, StandardCharsets.UTF_8)
                .build());
        body.add(fieldName, new HttpEntity<ByteArrayResource>(fileResource, fileHeaders));
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("读取上传文件失败: " + ex.getMessage(), ex);
        }
    }

    private Object parseResponse(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        try {
            return objectMapper.readValue(responseBody, Object.class);
        } catch (Exception ex) {
            return responseBody;
        }
    }
}
