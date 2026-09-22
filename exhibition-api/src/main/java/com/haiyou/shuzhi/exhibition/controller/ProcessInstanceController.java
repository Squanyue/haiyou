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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
public class ProcessInstanceController {

    private final ProcessInstanceService processInstanceService;
    private final ObjectMapper objectMapper;

    /**
     * 前端发起流程
     * <p>
     * multipart/form-data（与 EAD 发起接口一致，便于 Apifox 调试）：
     * - request：业务参数 JSON 字符串
     * - filesIcon：应用图标附件
     * - filesMaterials：资源压缩包附件
     * - filesAttachment：普通附件，可多个
     * - file：旧版通用附件参数，兼容保留并作为 filesAttachment 转发
     *
     * @param requestJson 前端业务入参 JSON
     * @param filesIcon 应用图标附件
     * @param filesMaterials 资源压缩包附件
     * @param filesAttachment 普通附件
     * @param files 旧版通用附件
     * @return EAD 响应
     */
    @PostMapping(value = "/processInstanceStart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Object> processInstanceStart(
            @RequestParam("request") String requestJson,
            @RequestParam(value = "filesIcon", required = false) MultipartFile[] filesIcon,
            @RequestParam(value = "filesMaterials", required = false) MultipartFile[] filesMaterials,
            @RequestParam(value = "filesAttachment", required = false) MultipartFile[] filesAttachment,
            @RequestParam(value = "file", required = false) MultipartFile[] files) {
        ProcessInstanceStartRequest request = parseRequest(requestJson);
        Map<String, MultipartFile[]> eadFilesByField = buildEadFilesByField(filesIcon, filesMaterials, filesAttachment, files);
        MultipartFile[] allFiles = mergeFiles(eadFilesByField);
        List<String> fileNames = collectFileNames(allFiles);
        log.info("前端调用 processInstanceStart, tableId={}, recordId={}, title={}, sysAndFlowCode={}, userAccount={}, personUserId={}, personUserIds={}, personUserIdType={}, eadFileFields={}, fileCount={}, fileNames={}",
                request.getTableId(),
                request.getRecordId(),
                request.getTitle(),
                request.getSysAndFlowCode(),
                request.getUserAccount(),
                request.getPersonUserId(),
                request.getPersonUserIds(),
                request.getPersonUserIdType(),
                eadFilesByField.keySet(),
                fileNames.size(),
                fileNames);
        Object response = processInstanceService.start(request, allFiles, eadFilesByField);
        return Result.ok(response);
    }

    private Map<String, MultipartFile[]> buildEadFilesByField(MultipartFile[] filesIcon,
                                                                MultipartFile[] filesMaterials,
                                                                MultipartFile[] filesAttachment,
                                                                MultipartFile[] legacyFiles) {
        Map<String, MultipartFile[]> filesByField = new LinkedHashMap<String, MultipartFile[]>();
        addFiles(filesByField, "filesIcon", filesIcon);
        addFiles(filesByField, "filesMaterials", filesMaterials);
        addFiles(filesByField, "filesAttachment", filesAttachment);
        mapLegacyFiles(filesByField, legacyFiles);
        return filesByField;
    }

    /**
     * 兼容当前 RPA 前端的同名 file 上传：图标、资源包、附件依次写入 form-data。
     * 图标为必传的第一个文件；其后第一个 ZIP/RAR 作为资源包，其余作为普通附件。
     */
    private void mapLegacyFiles(Map<String, MultipartFile[]> filesByField, MultipartFile[] legacyFiles) {
        if (legacyFiles == null || legacyFiles.length == 0) {
            return;
        }
        List<MultipartFile> remaining = new ArrayList<MultipartFile>();
        for (MultipartFile file : legacyFiles) {
            if (file != null && !file.isEmpty()) {
                remaining.add(file);
            }
        }
        if (remaining.isEmpty()) {
            return;
        }
        if (!filesByField.containsKey("filesIcon")) {
            addFiles(filesByField, "filesIcon", new MultipartFile[]{remaining.remove(0)});
        }
        if (!filesByField.containsKey("filesMaterials")) {
            for (int index = 0; index < remaining.size(); index++) {
                if (isArchive(remaining.get(index))) {
                    addFiles(filesByField, "filesMaterials", new MultipartFile[]{remaining.remove(index)});
                    break;
                }
            }
        }
        if (!remaining.isEmpty()) {
            addFiles(filesByField, "filesAttachment",
                    remaining.toArray(new MultipartFile[remaining.size()]));
        }
    }

    private boolean isArchive(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (!StringUtils.hasText(fileName)) {
            return false;
        }
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".zip") || lowerName.endsWith(".rar");
    }

    private void addFiles(Map<String, MultipartFile[]> filesByField, String fieldName, MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return;
        }
        MultipartFile[] existing = filesByField.get(fieldName);
        if (existing == null || existing.length == 0) {
            filesByField.put(fieldName, files);
            return;
        }
        MultipartFile[] merged = new MultipartFile[existing.length + files.length];
        System.arraycopy(existing, 0, merged, 0, existing.length);
        System.arraycopy(files, 0, merged, existing.length, files.length);
        filesByField.put(fieldName, merged);
    }

    private MultipartFile[] mergeFiles(Map<String, MultipartFile[]> filesByField) {
        List<MultipartFile> allFiles = new ArrayList<MultipartFile>();
        for (MultipartFile[] files : filesByField.values()) {
            if (files == null) {
                continue;
            }
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    allFiles.add(file);
                }
            }
        }
        return allFiles.toArray(new MultipartFile[allFiles.size()]);
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
            request = objectMapper.treeToValue(root, ProcessInstanceStartRequest.class);
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) ex;
            }
            throw new IllegalArgumentException("request JSON 解析失败: " + ex.getMessage(), ex);
        }
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
