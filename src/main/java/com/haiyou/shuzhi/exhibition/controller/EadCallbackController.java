package com.haiyou.shuzhi.exhibition.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haiyou.shuzhi.exhibition.common.FeishuConstants;
import com.haiyou.shuzhi.exhibition.common.Result;
import com.haiyou.shuzhi.exhibition.dto.ApprovalHandlingResult;
import com.haiyou.shuzhi.exhibition.dto.ApprovalReprocessRequest;
import com.haiyou.shuzhi.exhibition.dto.ApprovalStatusSnapshot;
import com.haiyou.shuzhi.exhibition.dto.EadGetTokenRequest;
import com.haiyou.shuzhi.exhibition.dto.EadTokenVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateVO;
import com.haiyou.shuzhi.exhibition.service.ApprovalResultProcessor;
import com.haiyou.shuzhi.exhibition.service.EadAuthService;
import com.haiyou.shuzhi.exhibition.service.EadApprovalResultService;
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
    private final EadApprovalResultService eadApprovalResultService;
    private final ApprovalResultProcessor approvalResultProcessor;

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

        Map<String, List<MultipartFile>> filesByField = Collections.emptyMap();
        if (request instanceof MultipartHttpServletRequest) {
            filesByField = resolveFilesByField((MultipartHttpServletRequest) request);
            if (!filesByField.isEmpty()) {
                // 先记下附件元信息，落盘失败时也能在日志里看到回调带了哪些文件。
                allParams.put("_incomingFiles", summarizeIncomingFiles(filesByField));
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

        // 必须先打完整入参再落盘：附件目录失败时否则看不到 EAD 回调参数。
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

        if (!filesByField.isEmpty()) {
            List<Map<String, Object>> savedFiles = eadCallbackFileService.saveCallbackFiles(filesByField);
            allParams.put("_files", savedFiles);
        }

        // 保持既有 /approved 地址与入参解析方式，新审批处理改由统一处理器执行。
        ApprovalHandlingResult handlingResult = eadApprovalResultService.handleResult(allParams);
        allParams.put("_approvalResult", handlingResult);

        return Result.ok("回调接收成功，已同步更新飞书多维表格", allParams);
    }

    /**
     * 手动重放审批结果落地（权限/积分/通知/状态）。
     * <p>
     * 适用：EAD 已回调成功，但飞书写表中途失败。
     * force=true 时可对已是「已通过」的申请做幂等续写。
     */
    @PostMapping("/reprocess")
    public Result<ApprovalHandlingResult> reprocess(@RequestBody ApprovalReprocessRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (!StringUtils.hasText(request.getBizUniqueKey())
                && !StringUtils.hasText(request.getProcessInstanceId())) {
            throw new IllegalArgumentException("bizUniqueKey 与 processInstanceId 至少传一个");
        }
        ApprovalStatusSnapshot snapshot = new ApprovalStatusSnapshot();
        snapshot.setBusinessUniqueKey(trimToNull(request.getBizUniqueKey()));
        snapshot.setApprovalInstanceId(trimToNull(request.getProcessInstanceId()));
        snapshot.setStatus(StringUtils.hasText(request.getApprovalStatus())
                ? request.getApprovalStatus().trim() : "结束");
        snapshot.setCurrentNode(trimToNull(request.getCurrentNode()));
        snapshot.setRejectionReason(trimToNull(request.getRejectionReason()));
        boolean force = Boolean.TRUE.equals(request.getForce());
        log.info("手动重放审批结果, bizUniqueKey={}, processInstanceId={}, status={}, force={}",
                snapshot.getBusinessUniqueKey(), snapshot.getApprovalInstanceId(),
                snapshot.getStatus(), force);
        ApprovalHandlingResult result = approvalResultProcessor.process(
                FeishuConstants.EAD_SOURCE, snapshot, force);
        return Result.ok(result);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private Map<String, List<MultipartFile>> resolveFilesByField(MultipartHttpServletRequest multipartRequest) {
        Map<String, List<MultipartFile>> filesByField = new LinkedHashMap<String, List<MultipartFile>>();
        Map<String, List<MultipartFile>> multiFileMap = multipartRequest.getMultiFileMap();
        if (multiFileMap != null && !multiFileMap.isEmpty()) {
            for (Map.Entry<String, List<MultipartFile>> entry : multiFileMap.entrySet()) {
                filesByField.put(entry.getKey(), entry.getValue());
            }
            return filesByField;
        }
        Map<String, MultipartFile> singleFileMap = multipartRequest.getFileMap();
        if (singleFileMap != null && !singleFileMap.isEmpty()) {
            for (Map.Entry<String, MultipartFile> entry : singleFileMap.entrySet()) {
                List<MultipartFile> list = new ArrayList<MultipartFile>();
                list.add(entry.getValue());
                filesByField.put(entry.getKey(), list);
            }
        }
        return filesByField;
    }

    private List<Map<String, Object>> summarizeIncomingFiles(Map<String, List<MultipartFile>> filesByField) {
        List<Map<String, Object>> summaries = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, List<MultipartFile>> entry : filesByField.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            for (MultipartFile file : entry.getValue()) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("field", entry.getKey());
                one.put("originalFilename", file.getOriginalFilename());
                one.put("size", file.getSize());
                one.put("contentType", file.getContentType());
                summaries.add(one);
            }
        }
        return summaries;
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
