package com.haiyou.shuzhi.exhibition.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haiyou.shuzhi.exhibition.common.Result;
import com.haiyou.shuzhi.exhibition.dto.EadGetTokenRequest;
import com.haiyou.shuzhi.exhibition.dto.EadTokenVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateVO;
import com.haiyou.shuzhi.exhibition.service.EadAuthService;
import com.haiyou.shuzhi.exhibition.service.EadCallbackFileService;
import com.haiyou.shuzhi.exhibition.service.EadCallbackService;
import com.haiyou.shuzhi.exhibition.service.EadProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EAD 相关接口
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Slf4j
@RestController
@RequestMapping("/api/ead-callbacks")
@RequiredArgsConstructor
public class EadCallbackController {

    private final ObjectMapper objectMapper;
    private final EadAuthService eadAuthService;
    private final EadProcessService eadProcessService;
    private final EadCallbackService eadCallbackService;
    private final EadCallbackFileService eadCallbackFileService;

    /**
     * 向 EAD 申请第三方系统令牌
     * <p>
     * 对应 EAD：POST /api/we-open/v1/wethirdpartysystemlogin/getToken
     * appId / appSecret 使用服务端配置，userAccount 由前端传入
     *
     * @param request 前端请求，仅需 userAccount
     * @return EAD 令牌信息
     */
    @PostMapping("/get-token")
    public Result<EadTokenVO> getToken(@Validated @RequestBody EadGetTokenRequest request) {
        log.info("前端申请 EAD 令牌, userAccount={}", request.getUserAccount());
        EadTokenVO tokenVO = eadAuthService.getToken(request.getUserAccount());
        return Result.ok(tokenVO);
    }

    /**
     * 发起 EAD 流程实例（前端参数转发）
     * <p>
     * 对应 EAD：POST /xcoa/api/framework/v1/extra-process-drive/process-instance/start
     * 自动调用 get-token 获取 Authorization，再以 multipart/form-data 转发
     *
     * @param userAccount         用户账号（用于换取 token）
     * @param createFlowInstance  流程 JSON，字段名与 EAD 一致
     * @param file                附件（可选）
     * @return EAD 响应
     */
    @PostMapping(value = "/process-instances/start", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Object> startProcess(
            @RequestParam("userAccount") String userAccount,
            @RequestParam("createFlowInstance") String createFlowInstance,
            @RequestParam(value = "file", required = false) MultipartFile[] files) {
        log.info("前端发起 EAD 流程, userAccount={}, createFlowInstance={}, fileCount={}",
                userAccount,
                createFlowInstance,
                files == null ? 0 : files.length);
        Object response = eadProcessService.startProcess(userAccount, createFlowInstance, files);
        return Result.ok(response);
    }

    /**
     * 接收 EAD 审批通过回调，原样记录全部入参（便于对照真实字段）
     *
     * @param request 原始请求
     * @return 收到的全部参数
     */
    @PostMapping("/approved")
    public Result<Map<String, Object>> receiveApprovedCallback(HttpServletRequest request) throws Exception {
        Map<String, Object> allParams = new LinkedHashMap<String, Object>();
        String contentType = request.getContentType();

        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap != null) {
            for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
                allParams.put(entry.getKey(), toSingleOrArray(entry.getValue()));
            }
        }

        if (request instanceof MultipartHttpServletRequest) {
            MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
            Map<String, List<MultipartFile>> filesByField = new LinkedHashMap<String, List<MultipartFile>>();
            Map<String, MultipartFile> singleFileMap = multipartRequest.getFileMap();
            Map<String, List<MultipartFile>> multiFileMap = multipartRequest.getMultiFileMap();

            if (multiFileMap != null && !multiFileMap.isEmpty()) {
                for (Map.Entry<String, List<MultipartFile>> entry : multiFileMap.entrySet()) {
                    filesByField.put(entry.getKey(), entry.getValue());
                }
            } else if (singleFileMap != null && !singleFileMap.isEmpty()) {
                for (Map.Entry<String, MultipartFile> entry : singleFileMap.entrySet()) {
                    List<MultipartFile> list = new ArrayList<MultipartFile>();
                    list.add(entry.getValue());
                    filesByField.put(entry.getKey(), list);
                }
            }

            if (!filesByField.isEmpty()) {
                List<Map<String, Object>> savedFiles = eadCallbackFileService.saveCallbackFiles(filesByField);
                allParams.put("_files", savedFiles);
            }
        }

        if (isJsonContentType(contentType) && allParams.isEmpty()) {
            String rawBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
            allParams.put("_rawBody", rawBody);
            if (StringUtils.hasText(rawBody)) {
                try {
                    Map<String, Object> jsonMap = objectMapper.readValue(
                            rawBody, new TypeReference<Map<String, Object>>() {
                            });
                    allParams.putAll(jsonMap);
                } catch (Exception ex) {
                    log.warn("JSON body 解析失败，已保留原始字符串: {}", ex.getMessage());
                }
            }
        }

        Map<String, String> headers = extractHeaders(request);

        log.info("========== EAD 回调原始入参开始 ==========");
        log.info("Content-Type: {}", contentType);
        log.info("Method: {}, URI: {}", request.getMethod(), request.getRequestURI());
        log.info("Request Headers: {}", headers);
        log.info("ParameterNames: {}", allParams.keySet());
        log.info("All Params ({} keys): {}", allParams.size(), allParams);
        for (Map.Entry<String, Object> entry : allParams.entrySet()) {
            log.info("EAD param => {} = {}", entry.getKey(), entry.getValue());
        }
        log.info("========== EAD 回调原始入参结束 ==========");

        // 回调成功后：更新飞书多维表格（无 record_id 时默认 recvqPN5Z2hVGl）
        FeishuRecordUpdateVO updateVO = eadCallbackService.handleApprovedCallback(allParams);
        allParams.put("_feishuUpdate", updateVO);

        return Result.ok("回调接收成功，已同步更新飞书多维表格", allParams);
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return Collections.emptyMap();
        }
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private Object toSingleOrArray(String[] values) {
        if (values == null) {
            return null;
        }
        if (values.length == 1) {
            return values[0];
        }
        return values;
    }

    private boolean isJsonContentType(String contentType) {
        return StringUtils.hasText(contentType) && contentType.toLowerCase().contains("application/json");
    }
}
