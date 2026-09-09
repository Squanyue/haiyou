package com.haiyou.shuzhi.exhibition.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.haiyou.shuzhi.exhibition.common.Result;
import com.haiyou.shuzhi.exhibition.dto.ProcessInstanceStartRequest;
import com.haiyou.shuzhi.exhibition.service.ProcessInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 流程实例接口（供前端调用）
 *
 * @author exhibition
 * @date 2026-07-30
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class ProcessInstanceController {

    private static final Set<String> REQUEST_FIELDS = new HashSet<String>(Arrays.asList(
            "tableId", "appToken", "recordId", "title", "sysAndFlowCode", "userAccount",
            "personUserId", "personUserIds", "personUserIdType", "approverDepartment",
            "ccDepartment", "fields", "detailFields"
    ));

    private final ProcessInstanceService processInstanceService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    /**
     * 前端发起流程
     * <p>
     * multipart/form-data（与 EAD 发起接口一致，便于 Apifox 调试）：
     * - request：业务参数 JSON 字符串
     * - file：附件，可多个（同名 file 重复传）
     *
     * @param requestJson 前端业务入参 JSON
     * @param files       附件，可选，支持多个
     * @return EAD 响应
     */
    @PostMapping(value = "/processInstanceStart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Object> processInstanceStart(
            @RequestParam("request") String requestJson,
            @RequestParam(value = "file", required = false) MultipartFile[] files) {
        ProcessInstanceStartRequest request = parseRequest(requestJson);
        List<String> fileNames = collectFileNames(files);
        log.info("前端调用 processInstanceStart, tableId={}, recordId={}, title={}, sysAndFlowCode={}, userAccount={}, personUserId={}, personUserIds={}, personUserIdType={}, fileCount={}, fileNames={}",
                request.getTableId(),
                request.getRecordId(),
                request.getTitle(),
                request.getSysAndFlowCode(),
                request.getUserAccount(),
                request.getPersonUserId(),
                request.getPersonUserIds(),
                request.getPersonUserIdType(),
                fileNames.size(),
                fileNames);
        Object response = processInstanceService.start(request, files);
        return Result.ok(response);
    }

    private ProcessInstanceStartRequest parseRequest(String requestJson) {
        if (!StringUtils.hasText(requestJson)) {
            throw new IllegalArgumentException("request 不能为空");
        }
        ProcessInstanceStartRequest request;
        try {
            JsonNode root = objectMapper.readTree(requestJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("request 必须是 JSON 对象");
            }
            /* 暂停 DTO 顶层字段严格校验，保留代码，后续需要时可恢复。
            Iterator<String> names = root.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!REQUEST_FIELDS.contains(name)) {
                    throw new IllegalArgumentException("request 不支持字段: " + name);
                }
            }
            */
            request = objectMapper.treeToValue(root, ProcessInstanceStartRequest.class);
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) ex;
            }
            throw new IllegalArgumentException("request JSON 解析失败: " + ex.getMessage(), ex);
        }
        /* 暂停 ProcessInstanceStartRequest Bean Validation，后续需要时可恢复。
        Set<ConstraintViolation<ProcessInstanceStartRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.iterator().next().getMessage());
        }
        */
        return request;
    }

    private List<String> collectFileNames(MultipartFile[] files) {
        List<String> names = new ArrayList<String>();
        if (files == null) {
            return names;
        }
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                names.add(file.getOriginalFilename());
            }
        }
        return names;
    }
}
