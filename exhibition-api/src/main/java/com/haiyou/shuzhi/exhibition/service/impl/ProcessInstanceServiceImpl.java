package com.haiyou.shuzhi.exhibition.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haiyou.shuzhi.exhibition.config.FeishuProperties;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordSearchRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordSearchVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateRequest;
import com.haiyou.shuzhi.exhibition.dto.ProcessInstanceStartRequest;
import com.haiyou.shuzhi.exhibition.service.EadProcessService;
import com.haiyou.shuzhi.exhibition.service.FeishuBitableService;
import com.haiyou.shuzhi.exhibition.service.FeishuMediaService;
import com.haiyou.shuzhi.exhibition.service.ProcessInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程发起业务服务实现
 *
 * @author exhibition
 * @date 2026-07-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessInstanceServiceImpl implements ProcessInstanceService {

    private static final String DEFAULT_USER_ACCOUNT = "linmm";
    private static final String DEFAULT_SYS_AND_FLOW_CODE = "test_ztcs";
    private static final String DEFAULT_ONBOARDING_APP_TOKEN = "WlrRbzs3ia2EUBsI2qscO7Ajn1g";
    private static final String DEFAULT_ONBOARDING_TABLE_ID = "tbl1Tvwl7t5RxcMs";
    private static final String APPLICATION_TYPE_TABLE_ID = "tbl3pZdlgPrWPwFQ";
    private static final String DEPARTMENT_DICTIONARY_TABLE_ID = "tbl5FYux2dx60VTo";
    private static final String BUSINESS_DOMAIN_DICTIONARY_TABLE_ID = "tbl1vUUXPPbDYRFK";
    private static final String SCENE_DICTIONARY_TABLE_ID = "tbl6vhBm9ICRfj90";
    private static final String STATUS_DICTIONARY_TABLE_ID = "tblwKZRKEWIpnGuX";
    private static final String RPA_DETAIL_TABLE_ID = "tbl5Ks8cFcZeaWNN";
    private static final String ATTACHMENT_TABLE_ID = "tbl4ImRqwcoGGpIX";
    private static final int MAX_ATTACHMENT_COUNT = 5;
    private static final long MAX_ATTACHMENT_SIZE = 512L * 1024L * 1024L;
    private static final long MAX_IMAGE_SIZE = 10L * 1024L * 1024L;
    /**
     * 飞书表格中标题对应字段名（按当前业务表调整）
     */
    private static final String FEISHU_TITLE_FIELD = "文本测试";
    private static final String FEISHU_IMAGE_FIELD = "图片测试";
    private static final String FEISHU_VIDEO_FIELD = "视频测试";
    private static final String FEISHU_PERSON_FIELD = "人员";
    private static final String FEISHU_SUPERVISOR_FIELD = "人员.直属上级";
    private static final String FEISHU_EMPLOYEE_NO_FIELD = "人员.工号";
    private static final String DEFAULT_DETAIL_RECORD_ID = "rechcNRGQB";
    private static final String DEFAULT_TEST_URL = "https://www.feishu.cn";
    private static final String DEFAULT_PERSON_USER_ID_TYPE = "open_id";
    /**
     * 当前目标应用索引表实际可写入的字段。目标表中的部分字段仍处于待定/未建状态，
     * 因此不能把前端表单字段原样透传给飞书，否则会触发 FieldNameNotFound。
     */
    private static final Set<String> ONBOARDING_WRITABLE_FIELDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "应用名称", "应用类型", "子类型（待定）", "摘要", "应用简介", "开发合作方信息",
            "应用URL地址", "移动端地址", "状态", "所属部门ID", "接入人AD账号", "联系电话",
            "联系邮箱", "适用用户AD账号", "适用部门ID", "适用角色", "权限范围", "申请人AD账号",
            "运营人员ID", "所属业务域ID", "所属场景ID", "适用对象"
    )));
    private static final Set<String> RPA_DETAIL_WRITABLE_FIELDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "应用编码", "版本号", "备注说明", "RPA所属平台", "操作流程步骤", "应用描述"
    )));
    private static final Set<String> GENERATED_FIELDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "应用ID", "主键", "应用图标", "应用Logo", "应用封面"
    )));

    private final EadProcessService eadProcessService;
    private final FeishuBitableService feishuBitableService;
    private final FeishuMediaService feishuMediaService;
    private final FeishuProperties feishuProperties;
    private final ObjectMapper objectMapper;

    @Override
    public Object start(ProcessInstanceStartRequest request, MultipartFile[] files) {
        /* 暂停新增请求校验，保留代码，后续需要时可恢复。
        if (StringUtils.hasText(request.getRecordId())) {
            throw new IllegalArgumentException("新增提交不允许传入 recordId");
        }
        validateAttachments(files);
        validateDetailFields(request.getDetailFields());
        */
        applyDefaults(request);

        // 1. 先调用飞书新增记录（含图片测试附件、人员），拿到 recordId
        String recordId = createFeishuRecord(request, files);
        request.setRecordId(recordId);

        // 应用索引写入成功后，再写入类型详情和附件资料。
        createRpaDetailRecord(request);
        createAttachmentRecords(request, files);

        // 2. 再调用 EAD 发起流程（透传附件，支持多个）
        String createFlowInstanceJson = buildCreateFlowInstanceJson(request);
        MultipartFile[] eadFiles = buildEadFiles(files);
        int fileCount = countFiles(eadFiles);
        log.info("组装 createFlowInstance 完成, tableId={}, recordId={}, userAccount={}, sysAndFlowCode={}, fileCount={}, personUserIds={}, json={}",
                request.getTableId(),
                request.getRecordId(),
                request.getUserAccount(),
                request.getSysAndFlowCode(),
                fileCount,
                resolvePersonUserIds(request),
                createFlowInstanceJson);
        return eadProcessService.startProcess(request.getUserAccount(), createFlowInstanceJson, eadFiles);
    }

    private MultipartFile[] buildEadFiles(MultipartFile[] files) {
        List<MultipartFile> eadFiles = new ArrayList<MultipartFile>();
        boolean hasVideo = false;
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                eadFiles.add(file);
                if (isVideoFile(file)) {
                    hasVideo = true;
                }
            }
        }
        if (!hasVideo) {
            String defaultVideoPath = feishuProperties.getDefaultVideoFile();
            if (!StringUtils.hasText(defaultVideoPath)) {
                log.info("前端未上传视频，且未配置默认视频，跳过视频附件");
                return eadFiles.toArray(new MultipartFile[eadFiles.size()]);
            }
            Path path = Paths.get(defaultVideoPath.trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                log.warn("默认视频文件不存在，跳过视频附件: {}", path);
                return eadFiles.toArray(new MultipartFile[eadFiles.size()]);
            }
            eadFiles.add(new LocalPathMultipartFile(path, "video/mp4"));
            log.info("前端未上传视频，EAD attachment4 使用默认视频, fileName={}", path.getFileName());
        }
        return eadFiles.toArray(new MultipartFile[eadFiles.size()]);
    }

    private int countFiles(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return 0;
        }
        int count = 0;
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private String createFeishuRecord(ProcessInstanceStartRequest request, MultipartFile[] files) {
        FeishuRecordCreateRequest createRequest = new FeishuRecordCreateRequest();
        createRequest.setAppToken(request.getAppToken());
        createRequest.setTableId(request.getTableId());
        createRequest.setUserIdType(resolvePersonUserIdType(request));

        Map<String, Object> submittedFields = request.getFields() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(request.getFields());
        if (submittedFields.isEmpty()) {
            throw new IllegalArgumentException("fields 不能为空，请传入应用索引表的实际字段值");
        }
        Map<String, Object> fields = normalizeOnboardingFields(submittedFields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("未找到目标应用索引表支持的字段");
        }
        /* 暂停应用类型和关联字典校验，保留代码，后续需要时可恢复。
        normalizeApplicationType(fields);
        validateDictionaryFields(fields);
        */
        normalizeGeneratedIdentifiers(fields);
        request.setFields(fields);
        createRequest.setFields(fields);

        log.info("processInstanceStart 先新增飞书记录, appToken={}, tableId={}, userIdType={}, fieldsKeys={}, attachmentCount={}",
                createRequest.getAppToken(),
                request.getTableId(),
                createRequest.getUserIdType(),
                fields.keySet(),
                files == null ? 0 : countFiles(files));
        FeishuRecordCreateVO createVO = feishuBitableService.createRecord(createRequest);
        if (createVO == null || createVO.getRecord() == null
                || !StringUtils.hasText(createVO.getRecord().getRecordId())) {
            throw new IllegalStateException("飞书新增记录成功但未返回 recordId");
        }
        String recordId = createVO.getRecord().getRecordId();
        log.info("飞书新增记录成功, recordId={}", recordId);
        return recordId;
    }

    private void normalizeGeneratedIdentifiers(Map<String, Object> fields) {
        // 应用 ID 属于系统自增编码，不能接受前端传入值，必须每次查询历史最大值后递增。
        fields.put("应用ID", nextApplicationId());
    }

    /**
     * 应用类型以“应用类型配置”表为准。前端可以传类型 ID、类型编码或类型名称，
     * 最终统一写入字典表中的“类型ID”。
     */
    private void normalizeApplicationType(Map<String, Object> fields) {
        String submittedType = textValue(fields.get("应用类型"));
        if (!StringUtils.hasText(submittedType)) {
            throw new IllegalArgumentException("应用类型不能为空");
        }
        String typeId = findApplicationTypeId(submittedType);
        fields.put("应用类型", typeId);
        log.info("应用类型已按字典表转换, submittedType={}, typeId={}", submittedType, typeId);
    }

    private String findApplicationTypeId(String submittedType) {
        return findDictionaryValue(
                APPLICATION_TYPE_TABLE_ID,
                submittedType,
                java.util.Arrays.asList("类型ID", "类型名称", "类型编码"),
                "类型ID",
                "应用类型");
    }

    private void validateDictionaryFields(Map<String, Object> fields) {
        validateDictionaryId(fields, "所属部门ID", DEPARTMENT_DICTIONARY_TABLE_ID, "部门ID");
        validateDictionaryId(fields, "所属业务域ID", BUSINESS_DOMAIN_DICTIONARY_TABLE_ID, "业务域ID");
        validateDictionaryId(fields, "所属场景ID", SCENE_DICTIONARY_TABLE_ID, "场景ID");

        String submittedStatus = textValue(fields.get("状态"));
        if (StringUtils.hasText(submittedStatus)) {
            String status = findDictionaryValue(
                    STATUS_DICTIONARY_TABLE_ID,
                    submittedStatus,
                    java.util.Arrays.asList("状态ID", "状态"),
                    "状态",
                    "状态");
            fields.put("状态", status);
            log.info("状态已按字典表转换, submittedStatus={}, status={}", submittedStatus, status);
        }
    }

    private void validateDictionaryId(Map<String, Object> fields, String submittedFieldName,
                                      String tableId, String dictionaryFieldName) {
        String submittedValue = textValue(fields.get(submittedFieldName));
        if (!StringUtils.hasText(submittedValue)) {
            return;
        }
        findDictionaryValue(
                tableId,
                submittedValue,
                Collections.singletonList(dictionaryFieldName),
                dictionaryFieldName,
                submittedFieldName);
    }

    private String findDictionaryValue(String tableId, String submittedValue,
                                       List<String> matchFieldNames, String returnFieldName,
                                       String submittedFieldName) {
        FeishuRecordSearchRequest searchRequest = new FeishuRecordSearchRequest();
        searchRequest.setAppToken(DEFAULT_ONBOARDING_APP_TOKEN);
        searchRequest.setTableId(tableId);
        searchRequest.setPageSize(500);
        searchRequest.setFieldNames(matchFieldNames);
        searchRequest.setAutomaticFields(Boolean.FALSE);

        do {
            FeishuRecordSearchVO response = feishuBitableService.searchRecords(searchRequest);
            if (response == null) {
                throw new IllegalStateException("查询字典表返回为空, tableId=" + tableId);
            }
            if (response.getItems() != null) {
                for (FeishuRecordSearchVO.RecordItem item : response.getItems()) {
                    if (item == null || item.getFields() == null) {
                        continue;
                    }
                    Map<String, Object> dictionaryFields = item.getFields();
                    boolean matched = false;
                    for (String matchFieldName : matchFieldNames) {
                        if (submittedValue.equalsIgnoreCase(fieldText(dictionaryFields.get(matchFieldName)))) {
                            matched = true;
                            break;
                        }
                    }
                    if (matched) {
                        String returnValue = fieldText(dictionaryFields.get(returnFieldName));
                        if (!StringUtils.hasText(returnValue)) {
                            throw new IllegalStateException("字典表匹配记录的返回字段为空, field=" + returnFieldName);
                        }
                        return returnValue;
                    }
                }
            }
            if (Boolean.TRUE.equals(response.getHasMore()) && StringUtils.hasText(response.getPageToken())) {
                searchRequest.setPageToken(response.getPageToken());
            } else {
                break;
            }
        } while (true);

        throw new IllegalArgumentException(submittedFieldName + "不是有效字典值: " + submittedValue);
    }

    private synchronized String nextApplicationId() {
        int max = -1;
        try {
            max = findHistoricalMaxNumber(
                    DEFAULT_ONBOARDING_APP_TOKEN,
                    DEFAULT_ONBOARDING_TABLE_ID,
                    "应用ID",
                    "APP");
        } catch (Exception ex) {
            // 历史查询失败不应阻断首次申请，按空表从 APP0001 开始。
            log.warn("查询应用索引历史编号失败，按 APP0001 作为起始编号继续: {}", ex.getMessage());
        }
        int nextNumber = max < 0 ? 1 : max + 1;
        if (nextNumber > 9999) {
            throw new IllegalStateException("应用ID已达到 APP9999，无法继续生成四位编号");
        }
        String next = "APP" + String.format("%04d", nextNumber);
        log.info("已查询应用索引历史最大应用编号, max={}, next={}", max < 0 ? "无" : max, next);
        return next;
    }

    private int findHistoricalMaxNumber(String appToken, String tableId, String fieldName, String prefix) {
        FeishuRecordSearchRequest searchRequest = new FeishuRecordSearchRequest();
        searchRequest.setAppToken(appToken);
        searchRequest.setTableId(tableId);
        searchRequest.setPageSize(500);
        searchRequest.setFieldNames(Collections.singletonList(fieldName));
        searchRequest.setAutomaticFields(Boolean.FALSE);

        int max = -1;
        do {
            FeishuRecordSearchVO response = feishuBitableService.searchRecords(searchRequest);
            if (response == null) {
                throw new IllegalStateException("查询历史编号返回为空");
            }
            if (response.getItems() != null) {
                for (FeishuRecordSearchVO.RecordItem item : response.getItems()) {
                    if (item == null || item.getFields() == null) {
                        continue;
                    }
                    int number = parseCodeNumber(item.getFields().get(fieldName), prefix);
                    if (number > max) {
                        max = number;
                    }
                }
            }
            if (Boolean.TRUE.equals(response.getHasMore()) && StringUtils.hasText(response.getPageToken())) {
                searchRequest.setPageToken(response.getPageToken());
            } else {
                break;
            }
        } while (true);
        return max;
    }

    private int parseCodeNumber(Object value, String prefix) {
        String text = textValue(value);
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            if (values.isEmpty()) {
                return 0;
            }
            Object first = values.get(0);
            if (first instanceof Map) {
                Object nested = ((Map<?, ?>) first).get("text");
                text = textValue(nested);
            } else {
                text = textValue(first);
            }
        } else if (value instanceof Map) {
            Object nested = ((Map<?, ?>) value).get("text");
            text = textValue(nested);
        }
        String upper = text.toUpperCase();
        String upperPrefix = prefix.toUpperCase();
        if (!upper.startsWith(upperPrefix)) {
            return 0;
        }
        String digits = upper.substring(upperPrefix.length()).replaceFirst("^[-_ ]+", "");
        if (!digits.matches("\\d+")) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void createRpaDetailRecord(ProcessInstanceStartRequest request) {
        Map<String, Object> sourceFields = request.getFields() == null
                ? new LinkedHashMap<String, Object>()
                : request.getFields();
        String applicationType = textValue(sourceFields.get("应用类型"));
        if (!isRpaType(applicationType)) {
            log.info("当前应用类型不是 RPA，跳过 RPA 应用详情表, applicationType={}", applicationType);
            return;
        }

        String applicationId = textValue(sourceFields.get("应用ID"));
        if (!StringUtils.hasText(applicationId)) {
            throw new IllegalArgumentException("应用ID不能为空，无法写入RPA应用详情表");
        }
        Map<String, Object> detailFields = request.getDetailFields() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(request.getDetailFields());
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        // RPA 主键属于系统自增编码，不能被 detailFields 覆盖，必须查询历史后递增。
        putText(fields, "主键", nextRpaDetailId());
        putText(fields, "应用ID", applicationId);
        // 应用编码是业务编码，未填写时保持为空，不用主键或应用 ID 冒充。
        putText(fields, "应用编码", detailFields.get("应用编码"));
        putText(fields, "版本号", valueOr(detailFields.get("版本号"), "V1.0"));
        putText(fields, "备注说明", detailFields.get("备注说明"));
        putText(fields, "RPA所属平台", detailFields.get("RPA所属平台"));
        putText(fields, "操作流程步骤", detailFields.get("操作流程步骤"));
        putText(fields, "应用描述", valueOr(detailFields.get("应用描述"), valueOr(sourceFields.get("摘要"), sourceFields.get("应用简介"))));
        createFeishuRecord(request.getAppToken(), RPA_DETAIL_TABLE_ID, fields, "RPA应用详情");
    }

    private void validateDetailFields(Map<String, Object> detailFields) {
        for (Map.Entry<String, Object> entry : detailFields.entrySet()) {
            String fieldName = entry.getKey();
            if (GENERATED_FIELDS.contains(fieldName) || "应用ID".equals(fieldName)) {
                throw new IllegalArgumentException("RPA详情字段不允许由前端传入: " + fieldName);
            }
            if (!RPA_DETAIL_WRITABLE_FIELDS.contains(fieldName)) {
                throw new IllegalArgumentException("RPA详情表不支持字段: " + fieldName);
            }
            Object value = entry.getValue();
            if (value != null && !(value instanceof String)) {
                throw new IllegalArgumentException("RPA详情字段必须是字符串: " + fieldName);
            }
            if (value instanceof String && ((String) value).trim().length() > 500) {
                throw new IllegalArgumentException("RPA详情字段超过长度限制: " + fieldName + "，最大 500 个字符");
            }
        }
    }

    private synchronized String nextRpaDetailId() {
        int max = -1;
        try {
            max = findHistoricalMaxNumber(
                    DEFAULT_ONBOARDING_APP_TOKEN,
                    RPA_DETAIL_TABLE_ID,
                    "主键",
                    "RPA");
        } catch (Exception ex) {
            log.warn("查询RPA详情历史主键失败，按 RPA0001 作为起始编号继续: {}", ex.getMessage());
        }
        int nextNumber = max < 0 ? 1 : max + 1;
        if (nextNumber > 9999) {
            throw new IllegalStateException("RPA主键已达到 RPA9999，无法继续生成四位编号");
        }
        String next = "RPA" + String.format("%04d", nextNumber);
        log.info("已查询RPA详情历史最大主键, max={}, next={}", max < 0 ? "无" : max, next);
        return next;
    }

    private void createAttachmentRecords(ProcessInstanceStartRequest request, MultipartFile[] files) {
        if (files == null || files.length == 0) {
            log.info("没有上传附件，跳过附件资料表");
            return;
        }
        Map<String, Object> sourceFields = request.getFields() == null
                ? new LinkedHashMap<String, Object>()
                : request.getFields();
        String applicationId = textValue(sourceFields.get("应用ID"));
        if (!StringUtils.hasText(applicationId)) {
            throw new IllegalArgumentException("应用ID不能为空，无法写入附件资料表");
        }
        String uploaderId = resolvePersonUserIds(request).isEmpty()
                ? ""
                : resolvePersonUserIds(request).get(0);
        int index = 0;
        int attachmentNumber = nextAttachmentNumber();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            index++;
            String fileName = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename()
                    : ("file_" + index);
            String fileToken = feishuMediaService.uploadBitableMedia(request.getAppToken(), file);
            Map<String, Object> fields = new LinkedHashMap<String, Object>();
            putText(fields, "主键", "ATT" + String.format("%04d", attachmentNumber++));
            putText(fields, "应用ID", applicationId);
            putText(fields, "文件名称", fileName);
            List<Map<String, String>> attachment = new ArrayList<Map<String, String>>(1);
            Map<String, String> attachmentValue = new HashMap<String, String>(2);
            attachmentValue.put("file_token", fileToken);
            attachment.add(attachmentValue);
            fields.put("附件", attachment);
            putText(fields, "文件大小", formatFileSize(file.getSize()));
            putText(fields, "上传时间", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            putText(fields, "上传人ID", uploaderId);
            createFeishuRecord(request.getAppToken(), ATTACHMENT_TABLE_ID, fields, "附件资料");
        }
        log.info("附件资料表写入完成, applicationId={}, count={}", applicationId, index);
    }

    private void validateAttachments(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return;
        }
        int count = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            count++;
            String fileName = file.getOriginalFilename();
            if (!StringUtils.hasText(fileName)) {
                throw new IllegalArgumentException("附件文件名不能为空");
            }
            if (fileName.contains("\\") || fileName.contains("/") || fileName.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("附件文件名不允许包含路径或非法字符: " + fileName);
            }
            if (file.getSize() > MAX_ATTACHMENT_SIZE) {
                throw new IllegalArgumentException("单个附件不能超过 512MB: " + fileName);
            }
            if (isImageFile(file) && file.getSize() > MAX_IMAGE_SIZE) {
                throw new IllegalArgumentException("图片附件不能超过 10MB: " + fileName);
            }
        }
        if (count > MAX_ATTACHMENT_COUNT) {
            throw new IllegalArgumentException("附件最多上传 5 个");
        }
    }

    private synchronized int nextAttachmentNumber() {
        int max = -1;
        try {
            max = findHistoricalMaxNumber(
                    DEFAULT_ONBOARDING_APP_TOKEN,
                    ATTACHMENT_TABLE_ID,
                    "主键",
                    "ATT");
        } catch (Exception ex) {
            log.warn("查询附件资料历史主键失败，按 ATT0001 作为起始编号继续: {}", ex.getMessage());
        }
        int nextNumber = max < 0 ? 1 : max + 1;
        if (nextNumber > 9999) {
            throw new IllegalStateException("附件资料主键已达到 ATT9999，无法继续生成四位编号");
        }
        String next = "ATT" + String.format("%04d", nextNumber);
        log.info("已查询附件资料历史最大主键, max={}, next={}", max < 0 ? "无" : max, next);
        return nextNumber;
    }

    private String createFeishuRecord(String appToken, String tableId, Map<String, Object> fields, String tableName) {
        FeishuRecordCreateRequest createRequest = new FeishuRecordCreateRequest();
        createRequest.setAppToken(appToken);
        createRequest.setTableId(tableId);
        createRequest.setUserIdType(DEFAULT_PERSON_USER_ID_TYPE);
        createRequest.setFields(fields);
        log.info("写入{}，tableId={}, fieldsKeys={}", tableName, tableId, fields.keySet());
        FeishuRecordCreateVO createVO = feishuBitableService.createRecord(createRequest);
        if (createVO == null || createVO.getRecord() == null
                || !StringUtils.hasText(createVO.getRecord().getRecordId())) {
            throw new IllegalStateException(tableName + "写入成功但未返回 recordId");
        }
        log.info("{}写入成功, recordId={}", tableName, createVO.getRecord().getRecordId());
        return createVO.getRecord().getRecordId();
    }

    private boolean isRpaType(String applicationType) {
        String normalized = applicationType == null ? "" : applicationType.trim();
        return "T003".equalsIgnoreCase(normalized)
                || "T0003".equalsIgnoreCase(normalized)
                || "RPA".equalsIgnoreCase(normalized)
                || normalized.contains("RPA");
    }

    private String fieldText(Object value) {
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            if (values.isEmpty()) {
                return "";
            }
            Object first = values.get(0);
            if (first instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) first;
                Object text = map.get("text");
                return StringUtils.hasText(textValue(text)) ? textValue(text) : textValue(map.get("value"));
            }
            return textValue(first);
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Object text = map.get("text");
            return StringUtils.hasText(textValue(text)) ? textValue(text) : textValue(map.get("value"));
        }
        return textValue(value);
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Object valueOr(Object value, Object fallback) {
        return StringUtils.hasText(textValue(value)) ? value : fallback;
    }

    private void putText(Map<String, Object> fields, String name, Object value) {
        if (value != null && StringUtils.hasText(textValue(value))) {
            fields.put(name, textValue(value));
        }
    }

    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        }
        return String.format("%.2f MB", size / (1024.0 * 1024.0));
    }

    private Map<String, Object> normalizeOnboardingFields(Map<String, Object> submittedFields) {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        /* 暂停应用索引字段严格校验，恢复为兼容性字段映射。
        for (Map.Entry<String, Object> entry : submittedFields.entrySet()) {
            String fieldName = entry.getKey();
            if (GENERATED_FIELDS.contains(fieldName)) {
                throw new IllegalArgumentException("字段不允许由前端传入: " + fieldName);
            }
            if (!ONBOARDING_WRITABLE_FIELDS.contains(fieldName) && !"子类型".equals(fieldName)) {
                throw new IllegalArgumentException("应用索引表不支持字段: " + fieldName);
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("字段必须是字符串: " + fieldName);
            }
            String text = ((String) value).trim();
            if (StringUtils.hasText(text)) {
                String targetFieldName = "子类型".equals(fieldName) ? "子类型（待定）" : fieldName;
                validateOnboardingFieldValue(targetFieldName, text);
                fields.put(targetFieldName, text);
            }
        }
        */
        for (String fieldName : ONBOARDING_WRITABLE_FIELDS) {
            Object value = submittedFields.get(fieldName);
            if (value != null && (!(value instanceof String) || StringUtils.hasText((String) value))) {
                fields.put(fieldName, value);
            }
        }
        // 兼容旧前端：只传“应用简介”时，同时作为目标表的摘要。
        if (!fields.containsKey("摘要")) {
            Object description = submittedFields.get("应用简介");
            if (description != null && (!(description instanceof String) || StringUtils.hasText((String) description))) {
                fields.put("摘要", description);
            }
        }
        if (!fields.containsKey("子类型（待定）")) {
            Object subtype = submittedFields.get("子类型");
            if (subtype != null && (!(subtype instanceof String) || StringUtils.hasText((String) subtype))) {
                fields.put("子类型（待定）", subtype);
            }
        }
        /* 暂停必填、长度、URL、邮箱校验。
        validateRequiredOnboardingFields(fields);
        validateUrl(fields, "应用URL地址", true);
        validateUrl(fields, "移动端地址", false);
        validateEmail(fields, "联系邮箱");
        */
        return fields;
    }

    private void validateRequiredOnboardingFields(Map<String, Object> fields) {
        String[] requiredFields = {"应用名称", "应用类型", "应用URL地址", "状态", "所属部门ID", "所属业务域ID", "所属场景ID"};
        for (String fieldName : requiredFields) {
            if (!StringUtils.hasText(textValue(fields.get(fieldName)))) {
                throw new IllegalArgumentException("应用索引表必填字段不能为空: " + fieldName);
            }
        }
    }

    private void validateOnboardingFieldValue(String fieldName, String value) {
        int maxLength = 200;
        if ("应用URL地址".equals(fieldName) || "移动端地址".equals(fieldName)) {
            maxLength = 2048;
        } else if ("摘要".equals(fieldName) || "应用简介".equals(fieldName)
                || "开发合作方信息".equals(fieldName) || "权限范围".equals(fieldName)) {
            maxLength = 500;
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("字段超过长度限制: " + fieldName + "，最大 " + maxLength + " 个字符");
        }
    }

    private void validateUrl(Map<String, Object> fields, String fieldName, boolean required) {
        String value = textValue(fields.get(fieldName));
        if (!StringUtils.hasText(value)) {
            if (required) {
                throw new IllegalArgumentException("URL不能为空: " + fieldName);
            }
            return;
        }
        if (!value.matches("https?://[^\\s]+")) {
            throw new IllegalArgumentException("URL格式不正确: " + fieldName);
        }
    }

    private void validateEmail(Map<String, Object> fields, String fieldName) {
        String value = textValue(fields.get(fieldName));
        if (StringUtils.hasText(value) && !value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("邮箱格式不正确: " + fieldName);
        }
    }

    private boolean isImageFile(MultipartFile file) {
        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType) && contentType.toLowerCase().startsWith("image/")) {
            return true;
        }
        String fileName = file.getOriginalFilename();
        if (!StringUtils.hasText(fileName)) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".webp");
    }

    private void updateDefaultPersonFields(ProcessInstanceStartRequest request, String recordId) {
        List<Map<String, String>> supervisors = buildDefaultSupervisorFieldValues();
        String employeeNo = feishuProperties.getDefaultPersonEmployeeNo();
        if (supervisors.isEmpty() && !StringUtils.hasText(employeeNo)) {
            return;
        }
        FeishuRecordUpdateRequest updateRequest = new FeishuRecordUpdateRequest();
        updateRequest.setTableId(request.getTableId());
        updateRequest.setRecordId(recordId);
        updateRequest.setUserIdType(resolvePersonUserIdType(request));
        updateRequest.setIgnoreConsistencyCheck(Boolean.TRUE);
        Map<String, Object> fields = new HashMap<String, Object>(4);
        if (!supervisors.isEmpty()) {
            fields.put(FEISHU_SUPERVISOR_FIELD, supervisors);
        }
        if (StringUtils.hasText(employeeNo)) {
            fields.put(FEISHU_EMPLOYEE_NO_FIELD, employeeNo.trim());
        }
        updateRequest.setFields(fields);
        feishuBitableService.updateRecord(updateRequest);
        log.info("飞书记录人员扩展字段已单独更新, recordId={}, supervisorUserId={}, employeeNo={}",
                recordId, feishuProperties.getDefaultSupervisorUserId(), employeeNo);
    }

    private List<Map<String, String>> buildPersonFieldValues(ProcessInstanceStartRequest request) {
        List<Map<String, String>> persons = new ArrayList<Map<String, String>>();
        for (String userId : resolvePersonUserIds(request)) {
            Map<String, String> item = new HashMap<String, String>(2);
            item.put("id", userId);
            persons.add(item);
        }
        return persons;
    }

    private List<Map<String, String>> buildDefaultSupervisorFieldValues() {
        List<Map<String, String>> supervisors = new ArrayList<Map<String, String>>();
        String supervisorUserId = feishuProperties.getDefaultSupervisorUserId();
        if (StringUtils.hasText(supervisorUserId)) {
            Map<String, String> item = new HashMap<String, String>(2);
            item.put("id", supervisorUserId.trim());
            supervisors.add(item);
        }
        return supervisors;
    }

    private Map<String, String> buildLinkValue(String text, String link) {
        Map<String, String> value = new HashMap<String, String>(2);
        value.put("text", text);
        value.put("link", link);
        return value;
    }

    private List<String> resolvePersonUserIds(ProcessInstanceStartRequest request) {
        List<String> ids = new ArrayList<String>();
        if (request.getPersonUserIds() != null) {
            for (String id : request.getPersonUserIds()) {
                if (StringUtils.hasText(id) && !ids.contains(id.trim())) {
                    ids.add(id.trim());
                }
            }
        }
        if (ids.isEmpty() && StringUtils.hasText(request.getPersonUserId())) {
            ids.add(request.getPersonUserId().trim());
        }
        if (ids.isEmpty() && StringUtils.hasText(feishuProperties.getDefaultPersonUserId())) {
            ids.add(feishuProperties.getDefaultPersonUserId().trim());
        }
        return ids;
    }

    private String resolvePersonUserIdType(ProcessInstanceStartRequest request) {
        if (StringUtils.hasText(request.getPersonUserIdType())) {
            return request.getPersonUserIdType().trim();
        }
        if (StringUtils.hasText(feishuProperties.getDefaultPersonUserIdType())) {
            return feishuProperties.getDefaultPersonUserIdType().trim();
        }
        return DEFAULT_PERSON_USER_ID_TYPE;
    }

    private Map<String, List<Map<String, String>>> uploadAttachments(String tableId, MultipartFile[] files) {
        Map<String, List<Map<String, String>>> attachmentsByField = new HashMap<String, List<Map<String, String>>>(2);
        List<Map<String, String>> imageAttachments = new ArrayList<Map<String, String>>();
        List<Map<String, String>> videoAttachments = new ArrayList<Map<String, String>>();
        attachmentsByField.put(FEISHU_IMAGE_FIELD, imageAttachments);
        attachmentsByField.put(FEISHU_VIDEO_FIELD, videoAttachments);
        if (files == null || files.length == 0) {
            return attachmentsByField;
        }
        String appToken = feishuProperties.getAppToken();
        if (!StringUtils.hasText(appToken)) {
            throw new IllegalStateException("未配置 feishu.app-token，无法上传附件到多维表格");
        }
        List<String> usedNames = new ArrayList<String>();
        int index = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            index++;
            String originalName = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename()
                    : ("file_" + index);
            String displayName = uniqueDisplayName(usedNames, originalName);
            usedNames.add(displayName);

            MultipartFile namedFile = new RenamedMultipartFile(file, displayName);
            String fileToken = feishuMediaService.uploadBitableMedia(appToken, namedFile);
            Map<String, String> item = new HashMap<String, String>(2);
            item.put("file_token", fileToken);
            String targetField = isVideoFile(file) ? FEISHU_VIDEO_FIELD : FEISHU_IMAGE_FIELD;
            attachmentsByField.get(targetField).add(item);
            log.info("飞书附件已上传, displayName={}, targetField={}, fileToken={}", displayName, targetField, fileToken);
        }
        log.info("飞书附件上传完成, tableId={}, imageCount={}, videoCount={}",
                tableId, imageAttachments.size(), videoAttachments.size());
        return attachmentsByField;
    }

    private void appendDefaultVideoIfMissing(String tableId, List<Map<String, String>> videoAttachments) {
        if (!videoAttachments.isEmpty()) {
            return;
        }
        String configuredToken = feishuProperties.getDefaultVideoFileToken();
        if (StringUtils.hasText(configuredToken)) {
            Map<String, String> item = new HashMap<String, String>(2);
            item.put("file_token", configuredToken.trim());
            videoAttachments.add(item);
            log.info("前端未上传视频，已复用默认视频素材, tableId={}, fileToken={}",
                    tableId, configuredToken.trim());
            return;
        }
        String configuredPath = feishuProperties.getDefaultVideoFile();
        if (!StringUtils.hasText(configuredPath)) {
            throw new IllegalStateException("前端未上传视频，且未配置 feishu.default-video-file");
        }
        Path path = Paths.get(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("默认视频文件不存在: " + path);
        }
        MultipartFile defaultVideo = new LocalPathMultipartFile(path, "video/mp4");
        String fileToken = feishuMediaService.uploadBitableMedia(feishuProperties.getAppToken(), defaultVideo);
        Map<String, String> item = new HashMap<String, String>(2);
        item.put("file_token", fileToken);
        videoAttachments.add(item);
        log.info("前端未上传视频，已写入默认视频, tableId={}, fileName={}, fileToken={}",
                tableId, defaultVideo.getOriginalFilename(), fileToken);
    }

    private boolean isVideoFile(MultipartFile file) {
        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType) && contentType.toLowerCase().startsWith("video/")) {
            return true;
        }
        String fileName = file.getOriginalFilename();
        if (!StringUtils.hasText(fileName)) {
            return false;
        }
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".mp4")
                || lowerName.endsWith(".mov")
                || lowerName.endsWith(".avi")
                || lowerName.endsWith(".mkv")
                || lowerName.endsWith(".webm")
                || lowerName.endsWith(".m4v");
    }

    private static final class LocalPathMultipartFile implements MultipartFile {

        private final Path path;
        private final String contentType;

        private LocalPathMultipartFile(Path path, String contentType) {
            this.path = path;
            this.contentType = contentType;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return path.getFileName().toString();
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return getSize() == 0L;
        }

        @Override
        public long getSize() {
            try {
                return Files.size(path);
            } catch (IOException ex) {
                throw new IllegalStateException("读取默认视频大小失败: " + path, ex);
            }
        }

        @Override
        public byte[] getBytes() throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public void transferTo(File destination) throws IOException, IllegalStateException {
            Files.copy(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String uniqueDisplayName(List<String> usedNames, String originalName) {
        String name = originalName.replace("\\", "_").replace("/", "_").trim();
        if (!StringUtils.hasText(name)) {
            name = "unnamed.bin";
        }
        if (!usedNames.contains(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int index = 2;
        String candidate;
        do {
            candidate = base + "_" + index + ext;
            index++;
        } while (usedNames.contains(candidate));
        return candidate;
    }

    private void applyDefaults(ProcessInstanceStartRequest request) {
        if (!StringUtils.hasText(request.getAppToken())) {
            request.setAppToken(DEFAULT_ONBOARDING_APP_TOKEN);
        }
        if (!StringUtils.hasText(request.getTableId())) {
            request.setTableId(DEFAULT_ONBOARDING_TABLE_ID);
        }
        if (!StringUtils.hasText(request.getUserAccount())) {
            request.setUserAccount(DEFAULT_USER_ACCOUNT);
            log.info("userAccount 为空，使用默认值: {}", DEFAULT_USER_ACCOUNT);
        }
        if (!StringUtils.hasText(request.getSysAndFlowCode())) {
            request.setSysAndFlowCode(DEFAULT_SYS_AND_FLOW_CODE);
            log.info("sysAndFlowCode 为空，使用默认值: {}", DEFAULT_SYS_AND_FLOW_CODE);
        }
    }

    private String buildCreateFlowInstanceJson(ProcessInstanceStartRequest request) {
        List<Map<String, String>> inputs = new ArrayList<Map<String, String>>();
        inputs.add(buildInput("title", request.getTitle()));
        inputs.add(buildInput("tableID", request.getTableId()));
        inputs.add(buildInput("recordId", request.getRecordId()));
        if (StringUtils.hasText(request.getApproverDepartment())) {
            inputs.add(buildInput("approverDepartment", request.getApproverDepartment()));
        }
        if (StringUtils.hasText(request.getCcDepartment())) {
            inputs.add(buildInput("ccDepartment", request.getCcDepartment()));
        }

        Map<String, Object> body = new HashMap<String, Object>(4);
        body.put("inputs", inputs);
        body.put("sysAndFlowCode", request.getSysAndFlowCode());

        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("组装 createFlowInstance JSON 失败: " + ex.getMessage(), ex);
        }
    }

    private Map<String, String> buildInput(String name, String value) {
        Map<String, String> input = new HashMap<String, String>(2);
        input.put("name", name);
        input.put("value", value);
        return input;
    }
}
