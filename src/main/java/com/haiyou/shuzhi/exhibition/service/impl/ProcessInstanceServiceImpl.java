package com.haiyou.shuzhi.exhibition.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haiyou.shuzhi.exhibition.common.EadConstants;
import com.haiyou.shuzhi.exhibition.common.FeishuConstants;
import com.haiyou.shuzhi.exhibition.config.EadProperties;
import com.haiyou.shuzhi.exhibition.config.FeishuProperties;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordDeleteRequest;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

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

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** EAD 下拉字段使用中文选项值；飞书内部字段仍保留业务编码。 */
    private static final Map<String, String> EAD_APPLICATION_TYPE_LABELS = optionMap(
            "T007", "可视化",
            "T006", "报表",
            "T003", "RPA",
            "T008", "数据集",
            "T009", "指标",
            "T001", "AI",
            "T005", "海能work应用",
            "T002", "EAD",
            "T004", "其他工具");
    private static final Map<String, String> EAD_DEPARTMENT_LABELS = optionMap(
            "D001", "信息化管理部",
            "D002", "数据智能部",
            "D003", "供应链管理部",
            "D004", "物资采购中心",
            "D005", "经营管理部");
    private static final Map<String, String> EAD_BUSINESS_DOMAIN_LABELS = optionMap(
            "BD001", "智能办公",
            "BD002", "综合管理",
            "BD003", "供应链管理",
            "BD004", "经营分析",
            "BD005", "数字化办公");

    /**
     * 单实例编号锁：保护“读取最大编号 + 写入应用索引/上架申请”这段临界区。
     * 多实例部署时必须替换为 Redis、数据库序列或其他分布式协调方案。
     */
    private static final ReentrantLock ID_GENERATION_LOCK = new ReentrantLock();
    /** 单实例内已分配但可能尚未写入多维表的申请单号。 */
    private final Map<String, String> reservedOnboardingApplicationNos = new HashMap<String, String>();
    private final EadProcessService eadProcessService;
    private final FeishuBitableService feishuBitableService;
    private final FeishuMediaService feishuMediaService;
    private final FeishuProperties feishuProperties;
    private final EadProperties eadProperties;
    private final ObjectMapper objectMapper;

    @Override
    public Object start(ProcessInstanceStartRequest request, MultipartFile[] files) {
        return start(request, files, buildLegacyEadFilesByField(files));
    }

    @Override
    public String reserveOnboardingApplicationNo(String uniqueIdentifier) {
        if (!StringUtils.hasText(uniqueIdentifier)) {
            throw new IllegalArgumentException("uniqueIdentifier 不能为空，无法预占申请单号");
        }
        String normalizedIdentifier = uniqueIdentifier.trim();
        acquireIdGenerationLock();
        try {
            String reserved = reservedOnboardingApplicationNos.get(normalizedIdentifier);
            if (StringUtils.hasText(reserved)) {
                return reserved;
            }
            String applicationNo = nextOnboardingApplicationNo();
            reservedOnboardingApplicationNos.put(normalizedIdentifier, applicationNo);
            return applicationNo;
        } finally {
            releaseIdGenerationLock();
        }
    }

    @Override
    public Object start(ProcessInstanceStartRequest request,
                        MultipartFile[] files,
                        Map<String, MultipartFile[]> eadFilesByField) {
        applyDefaults(request);
        boolean hainengWork = isHainengWorkApplication(request);

        String onboardingRecordId;
        acquireIdGenerationLock();
        try {
            try {
                // 锁必须覆盖“查最大编号 + 写表”，避免并发请求获得相同编号。
                String recordId = createFeishuRecord(request, files);
                request.setRecordId(recordId);

                // 海能 work 的上架申请已由前端创建，只回填关联应用 ID；其他类型由后端新增申请。
                if (hainengWork) {
                    onboardingRecordId = updateHainengWorkOnboardingApplication(request);
                } else {
                    onboardingRecordId = createOnboardingApplication(request);
                }

                // 应用索引写入成功后，再写入类型详情和附件资料。
                createRpaDetailRecord(request);
                createAttachmentRecords(request, files);
            } catch (RuntimeException ex) {
                // 写表中途失败：按唯一标识删除本笔已写入的记录。
                compensateDeleteWrittenTables(request, hainengWork, "多维表写入失败");
                throw ex;
            }
        } finally {
            releaseIdGenerationLock();
        }

        // 2. 非海能 work 走 EAD 审批；海能 work（T005）由前端走飞书审批，不发起 EAD。
        if (hainengWork) {
            log.info("海能work应用不走EAD审批, uniqueIdentifier={}, onboardingRecordId={}, applicationRecordId={}",
                    request.getUniqueIdentifier(), onboardingRecordId, request.getRecordId());
            Map<String, Object> skipped = new LinkedHashMap<String, Object>();
            skipped.put("success", true);
            skipped.put("skippedEad", true);
            skipped.put("reason", "海能work应用不走EAD审批");
            skipped.put("onboardingRecordId", onboardingRecordId);
            skipped.put("recordId", request.getRecordId());
            return skipped;
        }

        Map<String, MultipartFile[]> normalizedEadFiles = normalizeEadFilesByField(eadFilesByField);
        String createFlowInstanceJson = buildCreateFlowInstanceJson(request, normalizedEadFiles);
        int fileCount = countFiles(normalizedEadFiles);
        log.info("组装 createFlowInstance 完成, tableId={}, recordId={}, userAccount={}, sysAndFlowCode={}, eadFileFields={}, fileCount={}, personUserIds={}, json={}",
                request.getTableId(),
                request.getRecordId(),
                request.getUserAccount(),
                request.getSysAndFlowCode(),
                normalizedEadFiles.keySet(),
                fileCount,
                resolvePersonUserIds(request),
                createFlowInstanceJson);

        Object eadResponse;
        try {
            eadResponse = eadProcessService.startProcess(
                    request.getUserAccount(), createFlowInstanceJson, normalizedEadFiles);
        } catch (RuntimeException ex) {
            // EAD 可能已创建流程（如超时），不能删多维表，否则会出现悬空审批。
            log.error("EAD 发起异常，保留已写入的多维表数据, uniqueIdentifier={}, onboardingRecordId={}",
                    request.getUniqueIdentifier(), onboardingRecordId, ex);
            throw ex;
        }

        String instId = extractEadInstId(eadResponse);
        if (!StringUtils.hasText(instId)) {
            // 无 instId 时同样保留多维表，避免误删后与 EAD 侧已存在流程对不上。
            log.warn("EAD 未返回 instId，保留已写入的多维表数据, uniqueIdentifier={}, onboardingRecordId={}, response={}",
                    request.getUniqueIdentifier(), onboardingRecordId, eadResponse);
            if (isEadStartSuccess(eadResponse)) {
                throw new IllegalStateException("EAD 发起成功但未返回 instId");
            }
            return eadResponse;
        }

        try {
            bindEadInstanceToOnboarding(onboardingRecordId, request.getUniqueIdentifier(), eadResponse);
        } catch (RuntimeException ex) {
            // 流程已在 EAD 侧创建，不能再删表，否则会出现悬空审批。
            log.error("EAD 已返回 instId 但回写上架申请失败，保留多维表数据, uniqueIdentifier={}, instId={}, onboardingRecordId={}",
                    request.getUniqueIdentifier(), instId, onboardingRecordId, ex);
            throw ex;
        }
        return eadResponse;
    }

    private Map<String, MultipartFile[]> buildLegacyEadFilesByField(MultipartFile[] files) {
        Map<String, MultipartFile[]> filesByField = new LinkedHashMap<String, MultipartFile[]>();
        if (files != null && files.length > 0) {
            filesByField.put(EadConstants.FILES_ATTACHMENT_FIELD, files);
        }
        return filesByField;
    }

    private Map<String, MultipartFile[]> normalizeEadFilesByField(Map<String, MultipartFile[]> source) {
        Map<String, MultipartFile[]> result = new LinkedHashMap<String, MultipartFile[]>();
        addEadFiles(result, EadConstants.FILES_ICON_FIELD,
                source == null ? null : source.get(EadConstants.FILES_ICON_FIELD));
        addEadFiles(result, EadConstants.FILES_MATERIALS_FIELD,
                source == null ? null : source.get(EadConstants.FILES_MATERIALS_FIELD));
        addEadFiles(result, EadConstants.FILES_ATTACHMENT_FIELD,
                source == null ? null : source.get(EadConstants.FILES_ATTACHMENT_FIELD));
        return result;
    }

    private void addEadFiles(Map<String, MultipartFile[]> target, String fieldName, MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return;
        }
        List<MultipartFile> validFiles = new ArrayList<MultipartFile>();
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                validFiles.add(file);
            }
        }
        if (!validFiles.isEmpty()) {
            target.put(fieldName, validFiles.toArray(new MultipartFile[validFiles.size()]));
        }
    }

    private int countFiles(Map<String, MultipartFile[]> filesByField) {
        if (filesByField == null || filesByField.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (MultipartFile[] files : filesByField.values()) {
            if (files == null) {
                continue;
            }
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    count++;
                }
            }
        }
        return count;
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
        normalizeGeneratedIdentifiers(request, fields);
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

    private void normalizeGeneratedIdentifiers(ProcessInstanceStartRequest request,
                                               Map<String, Object> fields) {
        String uniqueIdentifierField = FeishuConstants.UNIQUE_IDENTIFIER_FIELD;
        String uniqueIdentifier = request.getUniqueIdentifier();
        if (!StringUtils.hasText(uniqueIdentifier)) {
            uniqueIdentifier = textValue(fields.get(uniqueIdentifierField));
        }
        if (!StringUtils.hasText(uniqueIdentifier)) {
            throw new IllegalArgumentException("uniqueIdentifier 不能为空");
        }
        uniqueIdentifier = uniqueIdentifier.trim();
        request.setUniqueIdentifier(uniqueIdentifier);
        fields.put(uniqueIdentifierField, uniqueIdentifier);

        // 应用 ID 属于系统自增编码，不能接受前端传入值，必须每次查询历史最大值后递增。
        fields.put(FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD, nextApplicationId());
        // 创建日期 / 最近更新日期是业务文本列，不等于飞书系统「创建时间」。
        String now = LocalDateTime.now().format(DATE_TIME_FORMATTER);
        if (!StringUtils.hasText(textValue(fields.get(FeishuConstants.APPLICATION_INDEX_CREATED_AT_FIELD)))) {
            fields.put(FeishuConstants.APPLICATION_INDEX_CREATED_AT_FIELD, now);
        }
        fields.put(FeishuConstants.APPLICATION_INDEX_UPDATED_AT_FIELD, now);
    }

    /**
     * 后端创建上架申请记录。申请单号按历史最大值递增，关联应用 ID 使用刚创建的应用索引记录。
     */
    private String createOnboardingApplication(ProcessInstanceStartRequest request) {
        FeishuProperties.ApprovalPolling approvalConfig = feishuProperties.getApprovalPolling();
        String appToken = requireConfig("feishu.approval-polling.app-token", approvalConfig.getAppToken());
        String tableId = requireConfig("feishu.approval-polling.table-id", approvalConfig.getTableId());
        String uniqueIdentifier = request.getUniqueIdentifier();
        if (!StringUtils.hasText(uniqueIdentifier)) {
            throw new IllegalArgumentException("uniqueIdentifier 不能为空，无法创建上架申请");
        }

        Map<String, Object> sourceFields = request.getFields() == null
                ? new LinkedHashMap<String, Object>()
                : request.getFields();
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put(FeishuConstants.APPLICATION_NO_FIELD, reserveOnboardingApplicationNo(uniqueIdentifier));
        fields.put(FeishuConstants.UNIQUE_IDENTIFIER_FIELD, uniqueIdentifier.trim());
        String applicationId = textValue(sourceFields.get(FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD));
        if (!StringUtils.hasText(applicationId)) {
            throw new IllegalStateException("应用索引未生成应用 ID，无法创建上架申请");
        }
        fields.put(FeishuConstants.APPLICATION_ID_FIELD, applicationId);
        putText(fields, FeishuConstants.APPLICATION_TYPE_ID_FIELD,
                sourceFields.get(FeishuConstants.APPLICATION_TYPE_FIELD));
        putText(fields, FeishuConstants.APPLICANT_ID_FIELD,
                sourceFields.get(FeishuConstants.APPLICANT_ACCOUNT_FIELD));
        putAuthorizedAudience(fields, sourceFields);
        // 落表先待提交，EAD 发起成功后再改为审批中。
        fields.put(FeishuConstants.STATUS_FIELD, FeishuConstants.TO_SUBMIT_STATUS);
        fields.put(FeishuConstants.SOURCE_FIELD, FeishuConstants.EAD_SOURCE);
        fields.put(FeishuConstants.CURRENT_NODE_FIELD, FeishuConstants.TO_SUBMIT_STATUS);
        fields.put("提交时间", LocalDateTime.now().format(DATE_TIME_FORMATTER));
        return createFeishuRecord(appToken, tableId, fields, "上架申请");
    }

    private String nextOnboardingApplicationNo() {
        FeishuProperties.ApprovalPolling approvalConfig = feishuProperties.getApprovalPolling();
        int max = findHistoricalMaxNumber(
                requireConfig("feishu.approval-polling.app-token", approvalConfig.getAppToken()),
                requireConfig("feishu.approval-polling.table-id", approvalConfig.getTableId()),
                FeishuConstants.APPLICATION_NO_FIELD,
                "PA");
        for (String reservedNo : reservedOnboardingApplicationNos.values()) {
            max = Math.max(max, parseCodeNumber(reservedNo, "PA"));
        }
        int nextNumber = max < 0 ? 1 : max + 1;
        String next = "PA" + String.format("%03d", nextNumber);
        log.info("已查询上架申请历史最大单号, max={}, next={}", max < 0 ? "无" : max, next);
        return next;
    }

    /**
     * 海能 work 应用的上架申请记录由前端预先创建；后端按唯一标识定位该记录并回填应用 ID。
     */
    private String updateHainengWorkOnboardingApplication(ProcessInstanceStartRequest request) {
        FeishuProperties.ApprovalPolling approvalConfig = feishuProperties.getApprovalPolling();
        String appToken = requireConfig("feishu.approval-polling.app-token", approvalConfig.getAppToken());
        String tableId = requireConfig("feishu.approval-polling.table-id", approvalConfig.getTableId());
        String uniqueIdentifier = request.getUniqueIdentifier();
        String applicationId = request.getFields() == null ? "" : textValue(
                request.getFields().get(FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD));
        if (!StringUtils.hasText(uniqueIdentifier) || !StringUtils.hasText(applicationId)) {
            throw new IllegalStateException("缺少唯一标识或应用 ID，无法更新海能work上架申请");
        }

        FeishuRecordSearchRequest searchRequest = new FeishuRecordSearchRequest();
        searchRequest.setAppToken(appToken);
        searchRequest.setTableId(tableId);
        searchRequest.setPageSize(100);
        searchRequest.setFieldNames(Collections.singletonList(FeishuConstants.UNIQUE_IDENTIFIER_FIELD));
        searchRequest.setAutomaticFields(Boolean.FALSE);
        searchRequest.setFilter(equalFilter(FeishuConstants.UNIQUE_IDENTIFIER_FIELD, uniqueIdentifier));
        List<FeishuRecordSearchVO.RecordItem> records = searchAllRecords(searchRequest);
        if (records.isEmpty()) {
            throw new IllegalStateException("海能work上架申请不存在唯一标识: " + uniqueIdentifier);
        }
        if (records.size() > 1) {
            throw new IllegalStateException("海能work上架申请存在重复唯一标识: " + uniqueIdentifier);
        }
        FeishuRecordSearchVO.RecordItem record = records.get(0);
        if (record == null || !StringUtils.hasText(record.getRecordId())) {
            throw new IllegalStateException("海能work上架申请缺少recordId，唯一标识: " + uniqueIdentifier);
        }

        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put(FeishuConstants.APPLICATION_ID_FIELD, applicationId);
        putAuthorizedAudience(fields, request.getFields());
        // 海能 work 走飞书审批，不写 EAD 审批来源。
        fields.put(FeishuConstants.SOURCE_FIELD, FeishuConstants.FEISHU_SOURCE);
        FeishuRecordUpdateRequest updateRequest = new FeishuRecordUpdateRequest();
        updateRequest.setAppToken(appToken);
        updateRequest.setTableId(tableId);
        updateRequest.setRecordId(record.getRecordId());
        updateRequest.setFields(fields);
        updateRequest.setIgnoreConsistencyCheck(Boolean.TRUE);
        feishuBitableService.updateRecord(updateRequest);
        log.info("已回填海能work上架申请关联应用ID, uniqueIdentifier={}, applicationId={}, recordId={}",
                uniqueIdentifier, applicationId, record.getRecordId());
        return record.getRecordId();
    }

    /**
     * EAD 发起成功后回写流程实例 ID，并把上架申请从「待提交」改为「审批中」。
     */
    private void bindEadInstanceToOnboarding(String onboardingRecordId, String uniqueIdentifier, Object eadResponse) {
        if (!StringUtils.hasText(onboardingRecordId)) {
            throw new IllegalStateException("上架申请缺少 recordId，无法回写审批实例ID");
        }
        String instId = extractEadInstId(eadResponse);
        if (!StringUtils.hasText(instId)) {
            throw new IllegalStateException("EAD 未返回 instId，无法回写上架申请");
        }

        FeishuProperties.ApprovalPolling approvalConfig = feishuProperties.getApprovalPolling();
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        if (StringUtils.hasText(uniqueIdentifier)) {
            fields.put(FeishuConstants.UNIQUE_IDENTIFIER_FIELD, uniqueIdentifier.trim());
        }
        fields.put(FeishuConstants.APPROVAL_INSTANCE_FIELD, instId);
        fields.put(FeishuConstants.SOURCE_FIELD, FeishuConstants.EAD_SOURCE);
        fields.put(FeishuConstants.STATUS_FIELD, FeishuConstants.PENDING_STATUS);
        fields.put(FeishuConstants.CURRENT_NODE_FIELD, "待审批");
        FeishuRecordUpdateRequest updateRequest = new FeishuRecordUpdateRequest();
        updateRequest.setAppToken(requireConfig("feishu.approval-polling.app-token", approvalConfig.getAppToken()));
        updateRequest.setTableId(requireConfig("feishu.approval-polling.table-id", approvalConfig.getTableId()));
        updateRequest.setRecordId(onboardingRecordId);
        updateRequest.setFields(fields);
        updateRequest.setIgnoreConsistencyCheck(Boolean.TRUE);
        feishuBitableService.updateRecord(updateRequest);
        log.info("已回写上架申请审批实例ID并改为审批中, recordId={}, instId={}, uniqueIdentifier={}",
                onboardingRecordId, instId, uniqueIdentifier);
    }

    /**
     * 按唯一标识补偿删除本笔已写入的多维表记录。
     * 删除顺序：附件 → RPA → 上架申请 → 应用索引。
     * 海能 work 上架申请由前端预创建，补偿时不删除该行。
     */
    private void compensateDeleteWrittenTables(ProcessInstanceStartRequest request,
                                               boolean preserveOnboardingApplication,
                                               String reason) {
        String uniqueIdentifier = request == null ? "" : textValue(request.getUniqueIdentifier());
        if (!StringUtils.hasText(uniqueIdentifier)) {
            log.warn("补偿删除跳过：唯一标识为空, reason={}", reason);
            return;
        }

        log.warn("开始补偿删除本笔多维表数据, uniqueIdentifier={}, preserveOnboarding={}, reason={}",
                uniqueIdentifier, preserveOnboardingApplication, reason);

        FeishuProperties.ProcessInstance processConfig = processInstanceConfig();
        FeishuProperties.ApprovalPolling approvalConfig = feishuProperties.getApprovalPolling();
        String processAppToken = requireConfig("feishu.process-instance.app-token", processConfig.getAppToken());
        String approvalAppToken = requireConfig("feishu.approval-polling.app-token", approvalConfig.getAppToken());

        deleteRecordsByUniqueIdentifier(processAppToken,
                requireConfig("feishu.process-instance.attachment-table-id", processConfig.getAttachmentTableId()),
                uniqueIdentifier, "附件资料");
        deleteRecordsByUniqueIdentifier(processAppToken,
                requireConfig("feishu.process-instance.rpa-detail-table-id", processConfig.getRpaDetailTableId()),
                uniqueIdentifier, "RPA应用详情");
        if (!preserveOnboardingApplication) {
            deleteRecordsByUniqueIdentifier(approvalAppToken,
                    requireConfig("feishu.approval-polling.table-id", approvalConfig.getTableId()),
                    uniqueIdentifier, "上架申请");
            reservedOnboardingApplicationNos.remove(uniqueIdentifier);
        }
        String applicationIndexTableId = StringUtils.hasText(request.getTableId())
                ? request.getTableId().trim()
                : requireConfig("feishu.process-instance.application-index-table-id",
                processConfig.getApplicationIndexTableId());
        String applicationIndexAppToken = StringUtils.hasText(request.getAppToken())
                ? request.getAppToken().trim()
                : processAppToken;
        deleteRecordsByUniqueIdentifier(applicationIndexAppToken, applicationIndexTableId,
                uniqueIdentifier, "应用索引");
        log.warn("补偿删除结束, uniqueIdentifier={}, reason={}", uniqueIdentifier, reason);
    }

    private void deleteRecordsByUniqueIdentifier(String appToken, String tableId,
                                                 String uniqueIdentifier, String tableName) {
        try {
            FeishuRecordSearchRequest searchRequest = new FeishuRecordSearchRequest();
            searchRequest.setAppToken(appToken);
            searchRequest.setTableId(tableId);
            searchRequest.setPageSize(100);
            searchRequest.setFieldNames(Collections.singletonList(FeishuConstants.UNIQUE_IDENTIFIER_FIELD));
            searchRequest.setAutomaticFields(Boolean.FALSE);
            searchRequest.setFilter(equalFilter(FeishuConstants.UNIQUE_IDENTIFIER_FIELD, uniqueIdentifier));
            List<FeishuRecordSearchVO.RecordItem> records = searchAllRecords(searchRequest);
            if (records.isEmpty()) {
                log.info("补偿删除未找到记录, tableName={}, tableId={}, uniqueIdentifier={}",
                        tableName, tableId, uniqueIdentifier);
                return;
            }
            int deleted = 0;
            for (FeishuRecordSearchVO.RecordItem item : records) {
                if (item == null || !StringUtils.hasText(item.getRecordId())) {
                    continue;
                }
                FeishuRecordDeleteRequest deleteRequest = new FeishuRecordDeleteRequest();
                deleteRequest.setAppToken(appToken);
                deleteRequest.setTableId(tableId);
                deleteRequest.setRecordId(item.getRecordId());
                feishuBitableService.deleteRecord(deleteRequest);
                deleted++;
            }
            log.info("补偿删除完成, tableName={}, tableId={}, uniqueIdentifier={}, deleted={}",
                    tableName, tableId, uniqueIdentifier, deleted);
        } catch (Exception ex) {
            // 补偿失败不覆盖原始业务异常，转人工按唯一标识清理。
            log.error("补偿删除失败，请人工按唯一标识清理, tableName={}, tableId={}, uniqueIdentifier={}",
                    tableName, tableId, uniqueIdentifier, ex);
        }
    }

    private String extractEadInstId(Object eadResponse) {
        Object instId = firstMapValue(eadResponse, "instId", "inst_id", "instanceId", "instance_id");
        return instId == null ? "" : String.valueOf(instId).trim();
    }

    private boolean isEadStartSuccess(Object eadResponse) {
        Object success = firstMapValue(eadResponse, "success");
        if (success instanceof Boolean) {
            return Boolean.TRUE.equals(success);
        }
        if (success != null && StringUtils.hasText(String.valueOf(success))) {
            return Boolean.parseBoolean(String.valueOf(success).trim());
        }
        Object code = firstMapValue(eadResponse, "code");
        return code != null && "200".equals(String.valueOf(code).trim());
    }

    private Object firstMapValue(Object source, String... names) {
        if (!(source instanceof Map) || names == null) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) source;
        for (String name : names) {
            if (map.containsKey(name) && map.get(name) != null) {
                return map.get(name);
            }
        }
        return null;
    }

    private List<FeishuRecordSearchVO.RecordItem> searchAllRecords(FeishuRecordSearchRequest request) {
        List<FeishuRecordSearchVO.RecordItem> records = new ArrayList<FeishuRecordSearchVO.RecordItem>();
        String pageToken = null;
        do {
            request.setPageToken(pageToken);
            FeishuRecordSearchVO response = feishuBitableService.searchRecords(request);
            if (response == null) {
                throw new IllegalStateException("查询上架申请返回为空");
            }
            if (response.getItems() != null) {
                records.addAll(response.getItems());
            }
            pageToken = Boolean.TRUE.equals(response.getHasMore()) && StringUtils.hasText(response.getPageToken())
                    ? response.getPageToken() : null;
        } while (StringUtils.hasText(pageToken));
        return records;
    }

    private FeishuRecordSearchRequest.FilterInfo equalFilter(String fieldName, String value) {
        FeishuRecordSearchRequest.Condition condition = new FeishuRecordSearchRequest.Condition();
        condition.setFieldName(fieldName);
        condition.setOperator("is");
        condition.setValue(Collections.singletonList(value));
        FeishuRecordSearchRequest.FilterInfo filter = new FeishuRecordSearchRequest.FilterInfo();
        filter.setConjunction("and");
        filter.setConditions(Collections.singletonList(condition));
        return filter;
    }

    private String nextApplicationId() {
        int max = findHistoricalMaxNumber(
                processInstanceConfig().getAppToken(),
                processInstanceConfig().getApplicationIndexTableId(),
                FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD,
                "APP");
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
        String applicationType = textValue(sourceFields.get(FeishuConstants.APPLICATION_TYPE_FIELD));
        if (!isRpaType(applicationType)) {
            log.info("当前应用类型不是 RPA，跳过 RPA 应用详情表, applicationType={}", applicationType);
            return;
        }

        String applicationId = textValue(sourceFields.get(FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD));
        if (!StringUtils.hasText(applicationId)) {
            throw new IllegalArgumentException("应用ID不能为空，无法写入RPA应用详情表");
        }
        Map<String, Object> detailFields = request.getDetailFields() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(request.getDetailFields());
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        // RPA 主键属于系统自增编码，不能被 detailFields 覆盖，必须查询历史后递增。
        putText(fields, FeishuConstants.PRIMARY_KEY_FIELD, nextRpaDetailId());
        putText(fields, FeishuConstants.UNIQUE_IDENTIFIER_FIELD, request.getUniqueIdentifier());
        putText(fields, FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD, applicationId);
        // 应用编码是业务编码，未填写时保持为空，不用主键或应用 ID 冒充。
        putText(fields, FeishuConstants.APPLICATION_CODE_FIELD, detailFields.get(FeishuConstants.APPLICATION_CODE_FIELD));
        putText(fields, FeishuConstants.VERSION_FIELD,
                valueOr(detailFields.get(FeishuConstants.VERSION_FIELD), "V1.0"));
        putText(fields, FeishuConstants.REMARKS_FIELD, detailFields.get(FeishuConstants.REMARKS_FIELD));
        putText(fields, FeishuConstants.RPA_PLATFORM_FIELD, detailFields.get(FeishuConstants.RPA_PLATFORM_FIELD));
        putText(fields, FeishuConstants.PROCESS_STEPS_FIELD, detailFields.get(FeishuConstants.PROCESS_STEPS_FIELD));
        putText(fields, FeishuConstants.APPLICATION_DESCRIPTION_FIELD,
                valueOr(detailFields.get(FeishuConstants.APPLICATION_DESCRIPTION_FIELD),
                        valueOr(sourceFields.get(FeishuConstants.SUMMARY_FIELD),
                                sourceFields.get(FeishuConstants.APPLICATION_INTRODUCTION_FIELD))));
        createFeishuRecord(request.getAppToken(), processInstanceConfig().getRpaDetailTableId(), fields, "RPA应用详情");
    }

    private String nextRpaDetailId() {
        int max = findHistoricalMaxNumber(
                processInstanceConfig().getAppToken(),
                processInstanceConfig().getRpaDetailTableId(),
                FeishuConstants.PRIMARY_KEY_FIELD,
                "RPA");
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
        String applicationId = textValue(sourceFields.get(FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD));
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
            putText(fields, FeishuConstants.PRIMARY_KEY_FIELD, "ATT" + String.format("%04d", attachmentNumber++));
            putText(fields, FeishuConstants.UNIQUE_IDENTIFIER_FIELD, request.getUniqueIdentifier());
            putText(fields, FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD, applicationId);
            putText(fields, "文件名称", fileName);
            List<Map<String, String>> attachment = new ArrayList<Map<String, String>>(1);
            Map<String, String> attachmentValue = new HashMap<String, String>(2);
            attachmentValue.put("file_token", fileToken);
            attachment.add(attachmentValue);
            fields.put(FeishuConstants.ATTACHMENT_FIELD, attachment);
            putText(fields, "文件大小", formatFileSize(file.getSize()));
            putText(fields, FeishuConstants.UPLOAD_TIME_FIELD,
                    LocalDateTime.now().format(DATE_TIME_FORMATTER));
            putText(fields, FeishuConstants.UPLOADER_ID_FIELD, uploaderId);
            createFeishuRecord(request.getAppToken(), processInstanceConfig().getAttachmentTableId(), fields, "附件资料");
        }
        log.info("附件资料表写入完成, applicationId={}, count={}", applicationId, index);
    }

    private int nextAttachmentNumber() {
        int max = findHistoricalMaxNumber(
                processInstanceConfig().getAppToken(),
                processInstanceConfig().getAttachmentTableId(),
                FeishuConstants.PRIMARY_KEY_FIELD,
                "ATT");
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
        createRequest.setUserIdType(requireConfig("feishu.default-person-user-id-type",
                feishuProperties.getDefaultPersonUserIdType()));
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

    /** 页面「适用部门/适用用户」写入上架申请「授权部门/授权用户」。 */
    private void putAuthorizedAudience(Map<String, Object> target, Map<String, Object> source) {
        if (source == null || target == null) {
            return;
        }
        putText(target, FeishuConstants.AUTHORIZED_DEPARTMENT_FIELD,
                source.get(FeishuConstants.APPLICABLE_DEPARTMENT_ID_FIELD));
        putText(target, FeishuConstants.AUTHORIZED_USER_FIELD,
                source.get(FeishuConstants.APPLICABLE_USER_ACCOUNT_FIELD));
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
        return new LinkedHashMap<String, Object>(submittedFields);
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
            fields.put(FeishuConstants.SUPERVISOR_FIELD, supervisors);
        }
        if (StringUtils.hasText(employeeNo)) {
            fields.put(FeishuConstants.EMPLOYEE_NO_FIELD, employeeNo.trim());
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
        return requireConfig("feishu.default-person-user-id-type",
                feishuProperties.getDefaultPersonUserIdType());
    }

    private Map<String, List<Map<String, String>>> uploadAttachments(String tableId, MultipartFile[] files) {
        Map<String, List<Map<String, String>>> attachmentsByField = new HashMap<String, List<Map<String, String>>>(2);
        List<Map<String, String>> imageAttachments = new ArrayList<Map<String, String>>();
        List<Map<String, String>> videoAttachments = new ArrayList<Map<String, String>>();
        attachmentsByField.put(FeishuConstants.IMAGE_FIELD, imageAttachments);
        attachmentsByField.put(FeishuConstants.VIDEO_FIELD, videoAttachments);
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
            String targetField = isVideoFile(file) ? FeishuConstants.VIDEO_FIELD : FeishuConstants.IMAGE_FIELD;
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
            request.setAppToken(requireConfig("feishu.process-instance.app-token",
                    processInstanceConfig().getAppToken()));
        }
        if (!StringUtils.hasText(request.getTableId())) {
            request.setTableId(requireConfig("feishu.process-instance.application-index-table-id",
                    processInstanceConfig().getApplicationIndexTableId()));
        }
        if (!StringUtils.hasText(request.getUserAccount())) {
            String defaultUserAccount = requireConfig("ead.default-user-account", eadProperties.getDefaultUserAccount());
            request.setUserAccount(defaultUserAccount);
            log.info("userAccount 为空，使用配置默认值: {}", defaultUserAccount);
        }
        if (isRpaApplication(request)) {
            // RPA 必须以 RPA 流程编码发起；忽略前端误传的通用默认值 test_ztcs。
            String rpaSysAndFlowCode = requireConfig("ead.rpa-sys-and-flow-code",
                    eadProperties.getRpaSysAndFlowCode());
            if (!rpaSysAndFlowCode.equals(request.getSysAndFlowCode())) {
                log.info("RPA 上架申请 sysAndFlowCode 从 [{} 调整为 RPA 流程配置: {}",
                        request.getSysAndFlowCode(), rpaSysAndFlowCode);
                request.setSysAndFlowCode(rpaSysAndFlowCode);
            }
        } else if (!StringUtils.hasText(request.getSysAndFlowCode())) {
            String defaultSysAndFlowCode = requireConfig("ead.default-sys-and-flow-code",
                    eadProperties.getDefaultSysAndFlowCode());
            request.setSysAndFlowCode(defaultSysAndFlowCode);
            log.info("sysAndFlowCode 为空，使用配置默认值: {}", defaultSysAndFlowCode);
        }
    }

    private boolean isRpaApplication(ProcessInstanceStartRequest request) {
        if (request.getFields() == null) {
            return false;
        }
        String applicationType = valueAsString(request.getFields().get(FeishuConstants.APPLICATION_TYPE_FIELD));
        if (isRpaType(applicationType)) {
            return true;
        }
        String rpaApplicationTypeCode = feishuProperties.getRpaApplicationTypeCode();
        return StringUtils.hasText(rpaApplicationTypeCode)
                && rpaApplicationTypeCode.trim().equalsIgnoreCase(applicationType.trim());
    }

    private boolean isHainengWorkApplication(ProcessInstanceStartRequest request) {
        if (request.getFields() == null) {
            return false;
        }
        return "T005".equalsIgnoreCase(valueAsString(
                request.getFields().get(FeishuConstants.APPLICATION_TYPE_FIELD)).trim());
    }

    private FeishuProperties.ProcessInstance processInstanceConfig() {
        return feishuProperties.getProcessInstance();
    }

    private String requireConfig(String propertyName, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("未配置 " + propertyName);
        }
        return value.trim();
    }

    private String buildCreateFlowInstanceJson(ProcessInstanceStartRequest request,
                                               Map<String, MultipartFile[]> eadFilesByField) {
        List<Map<String, String>> inputs = buildEadInputs(request, eadFilesByField);

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

    private List<Map<String, String>> buildEadInputs(ProcessInstanceStartRequest request,
                                                      Map<String, MultipartFile[]> eadFilesByField) {
        Map<String, Object> values = defaultEadInputValues(request);
        if (request.getEadInputs() != null) {
            values.putAll(request.getEadInputs());
        }
        // 唯一标识必须以本次上架申请为准，不能被前端 eadInputs 覆盖。
        values.put(EadConstants.BIZ_UNIQUE_KEY, request.getUniqueIdentifier());

        List<Map<String, String>> inputs = new ArrayList<Map<String, String>>();
        for (String inputName : EadConstants.INPUT_NAMES) {
            inputs.add(buildInput(inputName, valueAsString(values.get(inputName))));
        }
        inputs.add(buildInput(EadConstants.FILES_ICON_FIELD,
                buildEadFileMetadata(eadFilesByField.get(EadConstants.FILES_ICON_FIELD))));
        inputs.add(buildInput(EadConstants.FILES_MATERIALS_FIELD,
                buildEadFileMetadata(eadFilesByField.get(EadConstants.FILES_MATERIALS_FIELD))));
        inputs.add(buildInput(EadConstants.FILES_ATTACHMENT_FIELD,
                buildEadFileMetadata(eadFilesByField.get(EadConstants.FILES_ATTACHMENT_FIELD))));
        return inputs;
    }

    private Map<String, Object> defaultEadInputValues(ProcessInstanceStartRequest request) {
        Map<String, Object> sourceFields = request.getFields() == null
                ? Collections.<String, Object>emptyMap()
                : request.getFields();
        Map<String, Object> detailFields = request.getDetailFields() == null
                ? Collections.<String, Object>emptyMap()
                : request.getDetailFields();
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("applicant", sourceFields.get(FeishuConstants.APPLICANT_ACCOUNT_FIELD));
        values.put("department", eadOptionLabel(
                firstNonBlank(sourceFields.get(FeishuConstants.APPLICANT_DEPARTMENT_ID_FIELD),
                        sourceFields.get(FeishuConstants.DEPARTMENT_ID_FIELD)),
                EAD_DEPARTMENT_LABELS));
        values.put("phone", firstNonBlank(sourceFields.get(FeishuConstants.APPLICANT_PHONE_FIELD),
                sourceFields.get(FeishuConstants.PHONE_FIELD)));
        values.put("email", firstNonBlank(sourceFields.get(FeishuConstants.APPLICANT_EMAIL_FIELD),
                sourceFields.get(FeishuConstants.EMAIL_FIELD)));
        values.put("name", sourceFields.get(FeishuConstants.APPLICATION_NAME_FIELD));
        values.put("type", eadOptionLabel(sourceFields.get(FeishuConstants.APPLICATION_TYPE_FIELD),
                EAD_APPLICATION_TYPE_LABELS));
        values.put("domain", eadOptionLabel(sourceFields.get(FeishuConstants.BUSINESS_DOMAIN_ID_FIELD),
                EAD_BUSINESS_DOMAIN_LABELS));
        values.put("summary", sourceFields.get(FeishuConstants.SUMMARY_FIELD));
        values.put("scenario", sourceFields.get(FeishuConstants.APPLICATION_INTRODUCTION_FIELD));
        values.put("collaboration", sourceFields.get(FeishuConstants.COLLABORATION_FIELD));
        values.put("webAddress", sourceFields.get(FeishuConstants.WEB_ADDRESS_FIELD));
        values.put("mobileAddress", sourceFields.get(FeishuConstants.MOBILE_ADDRESS_FIELD));
        values.put("contactDepartment", eadOptionLabel(sourceFields.get(FeishuConstants.CONTACT_DEPARTMENT_ID_FIELD),
                EAD_DEPARTMENT_LABELS));
        values.put("contact", sourceFields.get(FeishuConstants.CONTACT_ACCOUNT_FIELD));
        values.put("contactPhone", sourceFields.get(FeishuConstants.PHONE_FIELD));
        values.put("contactEmail", sourceFields.get(FeishuConstants.EMAIL_FIELD));
        values.put("accessDepartment", eadOptionLabel(sourceFields.get(FeishuConstants.APPLICABLE_DEPARTMENT_ID_FIELD),
                EAD_DEPARTMENT_LABELS));
        values.put("users", sourceFields.get(FeishuConstants.APPLICABLE_USER_ACCOUNT_FIELD));
        values.put("roles", sourceFields.get(FeishuConstants.APPLICABLE_ROLE_FIELD));
        values.put("radio38", permissionScopeLabel(sourceFields.get(FeishuConstants.PERMISSION_SCOPE_FIELD)));
        values.put("remarks", detailFields.get(FeishuConstants.REMARKS_FIELD));
        values.put(EadConstants.BIZ_UNIQUE_KEY, request.getUniqueIdentifier());
        return values;
    }

    private static Map<String, String> optionMap(String... entries) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            result.put(entries[index], entries[index + 1]);
        }
        return Collections.unmodifiableMap(result);
    }

    private String eadOptionLabel(Object value, Map<String, String> labels) {
        String text = valueAsString(value);
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return labels.getOrDefault(text.trim(), text.trim());
    }

    private String permissionScopeLabel(Object value) {
        String text = valueAsString(value);
        return text.contains("全部") ? "全部组织可见" : "仅开放给部分部门/用户";
    }

    private String buildEadFileMetadata(MultipartFile[] files) {
        List<Map<String, String>> metadata = new ArrayList<Map<String, String>>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                Map<String, String> item = new LinkedHashMap<String, String>();
                item.put("fileName", StringUtils.hasText(file.getOriginalFilename())
                        ? file.getOriginalFilename() : "unnamed.bin");
                metadata.add(item);
            }
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("组装 EAD 附件元数据失败: " + ex.getMessage(), ex);
        }
    }

    private String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Object firstNonBlank(Object primary, Object fallback) {
        if (StringUtils.hasText(textValue(primary))) {
            return primary;
        }
        return fallback;
    }

    private void acquireIdGenerationLock() {
        if (!ID_GENERATION_LOCK.tryLock()) {
            throw new IllegalStateException("正在生成应用编号，请稍后重试");
        }
    }

    private void releaseIdGenerationLock() {
        if (ID_GENERATION_LOCK.isHeldByCurrentThread()) {
            ID_GENERATION_LOCK.unlock();
        }
    }

}
