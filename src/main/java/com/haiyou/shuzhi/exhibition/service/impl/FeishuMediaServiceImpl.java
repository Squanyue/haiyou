package com.haiyou.shuzhi.exhibition.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haiyou.shuzhi.exhibition.config.FeishuProperties;
import com.haiyou.shuzhi.exhibition.service.FeishuAuthService;
import com.haiyou.shuzhi.exhibition.service.FeishuMediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 飞书素材上传实现
 * <p>
 * ≤20MB：upload_all；&gt;20MB：分片流式上传 prepare → part → finish（支持最大约 2GB）
 *
 * @author exhibition
 * @date 2026-08-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuMediaServiceImpl implements FeishuMediaService {

    private static final String UPLOAD_ALL_PATH = "/open-apis/drive/v1/medias/upload_all";
    private static final String UPLOAD_PREPARE_PATH = "/open-apis/drive/v1/medias/upload_prepare";
    private static final String UPLOAD_PART_PATH = "/open-apis/drive/v1/medias/upload_part";
    private static final String UPLOAD_FINISH_PATH = "/open-apis/drive/v1/medias/upload_finish";
    private static final long UPLOAD_ALL_MAX_SIZE = 20L * 1024 * 1024;
    private static final long DEFAULT_ABSOLUTE_MAX_SIZE = 2147483647L; // 2GB-1，避免 int 溢出


    private final RestTemplate restTemplate;
    private final FeishuProperties feishuProperties;
    private final FeishuAuthService feishuAuthService;
    private final ObjectMapper objectMapper;

    @Override
    public String uploadBitableMedia(String appToken, MultipartFile file) {
        if (!StringUtils.hasText(appToken)) {
            throw new IllegalArgumentException("appToken 不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        long absoluteMax = resolveAbsoluteMaxSize();
        if (file.getSize() > absoluteMax) {
            throw new IllegalArgumentException("附件超过限制 "
                    + (absoluteMax / 1024 / 1024) + "MB: " + file.getOriginalFilename());
        }

        String accessToken = feishuAuthService.getTenantAccessToken();
        String displayName = normalizeDisplayName(file.getOriginalFilename());
        String parentType = resolveParentType(file, displayName);

        try {
            if (file.getSize() <= UPLOAD_ALL_MAX_SIZE) {
                return uploadAll(accessToken, appToken, parentType, displayName, file.getBytes());
            }
            log.info("附件超过 20MB，改用分片流式上传, fileName={}, size={}", displayName, file.getSize());
            return uploadByBlocks(accessToken, appToken, parentType, displayName, file);
        } catch (RestClientException ex) {
            log.error("调用飞书上传素材失败, fileName={}", displayName, ex);
            throw new IllegalStateException("调用飞书上传素材失败: " + ex.getMessage(), ex);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("读取上传文件失败: " + displayName + ", " + ex.getMessage(), ex);
        }
    }

    private long resolveAbsoluteMaxSize() {
        Long configured = feishuProperties.getMediaMaxSizeBytes();
        if (configured != null && configured > 0) {
            return configured;
        }
        return DEFAULT_ABSOLUTE_MAX_SIZE;
    }

    private String uploadAll(String accessToken, String appToken, String parentType,
                             String displayName, byte[] bytes) {
        final String wireFileName = buildWireFileName(displayName);
        ByteArrayResource fileResource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return wireFileName;
            }
        };

        HttpHeaders textHeaders = utf8TextHeaders();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        fileHeaders.setContentDisposition(ContentDisposition.builder("form-data")
                .name("file")
                .filename(displayName, StandardCharsets.UTF_8)
                .build());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<String, Object>();
        body.add("file_name", new HttpEntity<String>(displayName, textHeaders));
        body.add("parent_type", new HttpEntity<String>(parentType, textHeaders));
        body.add("parent_node", new HttpEntity<String>(appToken, textHeaders));
        body.add("size", new HttpEntity<String>(String.valueOf(bytes.length), textHeaders));
        body.add("file", new HttpEntity<ByteArrayResource>(fileResource, fileHeaders));

        String url = feishuProperties.getBaseUrl() + UPLOAD_ALL_PATH;
        log.info("调用飞书整文件上传, appToken={}, parentType={}, displayName={}, size={}",
                appToken, parentType, displayName, bytes.length);
        String responseBody = postMultipart(accessToken, url, body);
        return parseFileToken(responseBody, displayName);
    }

    private String uploadByBlocks(String accessToken, String appToken, String parentType,
                                  String displayName, MultipartFile file) throws Exception {
        long fileSize = file.getSize();
        JsonNode prepareData = prepareUpload(accessToken, appToken, parentType, displayName, fileSize);
        String uploadId = prepareData.path("upload_id").asText(null);
        int blockSize = prepareData.path("block_size").asInt(4 * 1024 * 1024);
        int blockNum = prepareData.path("block_num").asInt(0);
        if (!StringUtils.hasText(uploadId) || blockSize <= 0 || blockNum <= 0) {
            throw new IllegalStateException("飞书分片预上传返回异常: upload_id/block_size/block_num 无效");
        }

        log.info("飞书分片预上传成功, fileName={}, uploadId={}, blockSize={}, blockNum={}, fileSize={}",
                displayName, uploadId, blockSize, blockNum, fileSize);

        InputStream inputStream = file.getInputStream();
        try {
            byte[] buffer = new byte[blockSize];
            for (int seq = 0; seq < blockNum; seq++) {
                int offset = 0;
                while (offset < blockSize) {
                    int read = inputStream.read(buffer, offset, blockSize - offset);
                    if (read < 0) {
                        break;
                    }
                    offset += read;
                }
                if (offset <= 0) {
                    throw new IllegalStateException("飞书分片读取失败，提前结束: seq=" + seq);
                }
                byte[] part = offset == buffer.length ? buffer : copyOf(buffer, offset);
                uploadPart(accessToken, uploadId, seq, part);
                log.info("飞书分片上传进度, fileName={}, seq={}/{}, partSize={}",
                        displayName, seq + 1, blockNum, part.length);
            }
        } finally {
            try {
                inputStream.close();
            } catch (Exception ignore) {
                // ignore
            }
        }

        return finishUpload(accessToken, uploadId, blockNum, displayName);
    }

    private byte[] copyOf(byte[] source, int length) {
        byte[] part = new byte[length];
        System.arraycopy(source, 0, part, 0, length);
        return part;
    }

    private JsonNode prepareUpload(String accessToken, String appToken, String parentType,
                                   String displayName, long size) {
        Map<String, Object> body = new HashMap<String, Object>(8);
        body.put("file_name", displayName);
        body.put("parent_type", parentType);
        body.put("parent_node", appToken);
        body.put("size", size);

        String url = feishuProperties.getBaseUrl() + UPLOAD_PREPARE_PATH;
        String responseBody = postJson(accessToken, url, body);
        return requireData(responseBody, "分片预上传");
    }

    private void uploadPart(String accessToken, String uploadId, int seq, byte[] partBytes) {
        final String partName = "part_" + seq + ".bin";
        ByteArrayResource partResource = new ByteArrayResource(partBytes) {
            @Override
            public String getFilename() {
                return partName;
            }
        };

        HttpHeaders textHeaders = utf8TextHeaders();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        fileHeaders.setContentDisposition(ContentDisposition.builder("form-data")
                .name("file")
                .filename(partName, StandardCharsets.UTF_8)
                .build());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<String, Object>();
        body.add("upload_id", new HttpEntity<String>(uploadId, textHeaders));
        body.add("seq", new HttpEntity<String>(String.valueOf(seq), textHeaders));
        body.add("size", new HttpEntity<String>(String.valueOf(partBytes.length), textHeaders));
        body.add("file", new HttpEntity<ByteArrayResource>(partResource, fileHeaders));

        String url = feishuProperties.getBaseUrl() + UPLOAD_PART_PATH;
        String responseBody = postMultipart(accessToken, url, body);
        requireSuccess(responseBody, "分片上传 seq=" + seq);
    }

    private String finishUpload(String accessToken, String uploadId, int blockNum, String displayName) {
        Map<String, Object> body = new HashMap<String, Object>(4);
        body.put("upload_id", uploadId);
        body.put("block_num", blockNum);

        String url = feishuProperties.getBaseUrl() + UPLOAD_FINISH_PATH;
        String responseBody = postJson(accessToken, url, body);
        String fileToken = requireData(responseBody, "分片完成上传").path("file_token").asText(null);
        if (!StringUtils.hasText(fileToken)) {
            throw new IllegalStateException("飞书分片完成上传成功但未返回 file_token: " + displayName);
        }
        log.info("飞书分片上传完成, fileName={}, fileToken={}", displayName, fileToken);
        return fileToken;
    }

    private String postJson(String accessToken, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<Object>(body, headers), String.class);
        return response.getBody();
    }

    private String postMultipart(String accessToken, String url, MultiValueMap<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url, new HttpEntity<MultiValueMap<String, Object>>(body, headers), String.class);
        return response.getBody();
    }

    private HttpHeaders utf8TextHeaders() {
        HttpHeaders textHeaders = new HttpHeaders();
        textHeaders.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        return textHeaders;
    }

    private JsonNode requireData(String responseBody, String actionName) {
        JsonNode root = parseRoot(responseBody, actionName);
        requireSuccessCode(root, actionName);
        return root.path("data");
    }

    private void requireSuccess(String responseBody, String actionName) {
        JsonNode root = parseRoot(responseBody, actionName);
        requireSuccessCode(root, actionName);
    }

    private JsonNode parseRoot(String responseBody, String actionName) {
        if (!StringUtils.hasText(responseBody)) {
            throw new IllegalStateException("飞书" + actionName + "返回为空");
        }
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            throw new IllegalStateException("解析飞书" + actionName + "响应失败: " + ex.getMessage(), ex);
        }
    }

    private void requireSuccessCode(JsonNode root, String actionName) {
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String msg = root.path("msg").asText("未知错误");
            throw new IllegalStateException("飞书" + actionName + "失败, code=" + code + ", msg=" + msg);
        }
    }

    private String parseFileToken(String responseBody, String fileName) {
        String fileToken = requireData(responseBody, "整文件上传").path("file_token").asText(null);
        if (!StringUtils.hasText(fileToken)) {
            throw new IllegalStateException("飞书上传素材成功但未返回 file_token: " + fileName);
        }
        log.info("飞书整文件上传成功, fileName={}, fileToken={}", fileName, fileToken);
        return fileToken;
    }

    private String normalizeDisplayName(String originalFilename) {
        String name = StringUtils.hasText(originalFilename) ? originalFilename.trim() : "unnamed";
        name = name.replace("\\", "_").replace("/", "_");
        if (!StringUtils.hasText(name)) {
            return "unnamed.bin";
        }
        return name;
    }

    private String buildWireFileName(String displayName) {
        String ext = "";
        int dot = displayName.lastIndexOf('.');
        if (dot >= 0 && dot < displayName.length() - 1) {
            ext = displayName.substring(dot);
            if (!ext.matches("\\.[A-Za-z0-9]{1,10}")) {
                ext = ".bin";
            }
        }
        return "upload_" + System.nanoTime() + ext;
    }

    private String resolveParentType(MultipartFile file, String displayName) {
        String contentType = file.getContentType();
        if ((contentType != null && contentType.startsWith("image/"))
                || displayName.matches("(?i).+\\.(png|jpe?g|gif|bmp|webp|svg)$")) {
            return "bitable_image";
        }
        return "bitable_file";
    }
}
