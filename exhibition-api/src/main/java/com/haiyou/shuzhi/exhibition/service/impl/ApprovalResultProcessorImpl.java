package com.haiyou.shuzhi.exhibition.service.impl;

import com.haiyou.shuzhi.exhibition.common.FeishuConstants;
import com.haiyou.shuzhi.exhibition.config.FeishuProperties;
import com.haiyou.shuzhi.exhibition.dto.ApprovalHandlingResult;
import com.haiyou.shuzhi.exhibition.dto.ApprovalStatusSnapshot;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordSearchRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordSearchVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateRequest;
import com.haiyou.shuzhi.exhibition.service.ApprovalResultProcessor;
import com.haiyou.shuzhi.exhibition.service.FeishuApprovalService;
import com.haiyou.shuzhi.exhibition.service.FeishuBitableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞书轮询和 EAD 回调共用的审批结果处理器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalResultProcessorImpl implements ApprovalResultProcessor {

    private static final String LOCK_PREFIX = "exhibition:approval:";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int APPLICATION_OWNER_POINTS = 30;
    private static final int APPLICATION_DEVELOPER_POINTS = 30;
    private static final int ONBOARDING_APPLICANT_POINTS = 10;
    private static final int APPLICATION_USE_APPLICANT_POINTS = 5;
    private static final int APPLICATION_USE_OWNER_POINTS = 1;
    private static final int APPLICATION_USE_DEVELOPER_POINTS = 1;
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<Long>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final FeishuBitableService feishuBitableService;
    private final FeishuApprovalService feishuApprovalService;
    private final FeishuProperties feishuProperties;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void pollFeishuOnboardingApprovals() {
        pollFeishuApprovalsByType(RequestType.ONBOARDING, "onboarding",
                Boolean.TRUE.equals(config().getOnboardingEnabled()));
    }

    @Override
    public void pollFeishuUseApprovals() {
        pollFeishuApprovalsByType(RequestType.USE, "use",
                Boolean.TRUE.equals(config().getUseEnabled()));
    }

    /**
     * 按申请类型独立轮询飞书审批，互不影响开关、锁和异常。
     */
    private void pollFeishuApprovalsByType(RequestType type, String lockSuffix, boolean typeEnabled) {
        FeishuProperties.ApprovalPolling config = config();
        if (!Boolean.TRUE.equals(config.getEnabled()) || !typeEnabled) {
            return;
        }
        validatePollingConfiguration();

        String lockValue = UUID.randomUUID().toString();
        String lockKey = LOCK_PREFIX + "polling:feishu:" + lockSuffix;
        if (!tryLock(lockKey, lockValue)) {
            log.info("飞书{}审批轮询任务正在其他实例执行，本次跳过", type);
            return;
        }

        try {
            List<ApprovalRequestRecord> records = findPendingRecordsSafely(
                    type, FeishuConstants.FEISHU_SOURCE, null, null);
            log.info("飞书{}审批轮询开始，pendingCount={}", type, records.size());

            for (ApprovalRequestRecord record : records) {
                try {
                    ApprovalStatusSnapshot snapshot = queryFeishuWithRetry(record.getApprovalInstanceId());
                    process(FeishuConstants.FEISHU_SOURCE, snapshot);
                } catch (Exception ex) {
                    // 单条失败不阻断其它申请；该记录仍保持审批中，下一轮继续处理。
                    log.error("飞书审批轮询处理失败，requestType={}, recordId={}, instanceId={}",
                            record.getType(), record.getRecordId(), record.getApprovalInstanceId(), ex);
                }
            }
        } finally {
            releaseLock(lockKey, lockValue);
        }
    }

    @Override
    public ApprovalHandlingResult process(String approvalSource, ApprovalStatusSnapshot snapshot) {
        return process(approvalSource, snapshot, false);
    }

    @Override
    public ApprovalHandlingResult process(String approvalSource, ApprovalStatusSnapshot snapshot, boolean force) {
        validatePollingConfiguration();
        if (!StringUtils.hasText(approvalSource)) {
            throw new IllegalArgumentException("审批来源不能为空");
        }
        if (snapshot == null
                || (!StringUtils.hasText(snapshot.getApprovalInstanceId())
                && !StringUtils.hasText(snapshot.getBusinessUniqueKey()))) {
            throw new IllegalArgumentException("审批结果中的审批实例ID或业务唯一标识不能为空");
        }
        if (!StringUtils.hasText(snapshot.getStatus())) {
            throw new IllegalArgumentException("审批结果中的审批状态不能为空");
        }

        ApprovalState state = ApprovalState.from(snapshot.getStatus());
        if (state == ApprovalState.PENDING) {
            return skipped("审批仍在处理中，不执行后续业务");
        }
        if (state == ApprovalState.UNKNOWN) {
            throw new IllegalArgumentException("不支持的审批状态: " + snapshot.getStatus());
        }

        String instanceId = StringUtils.hasText(snapshot.getApprovalInstanceId())
                ? snapshot.getApprovalInstanceId().trim() : null;
        String businessUniqueKey = StringUtils.hasText(snapshot.getBusinessUniqueKey())
                ? snapshot.getBusinessUniqueKey().trim() : null;
        String source = approvalSource.trim();
        String lockIdentity = StringUtils.hasText(businessUniqueKey)
                ? "business:" + businessUniqueKey : "instance:" + instanceId;
        String lockKey = LOCK_PREFIX + lockIdentity + ":" + source;
        String lockValue = UUID.randomUUID().toString();
        if (!tryLock(lockKey, lockValue)) {
            return skipped("同一审批实例正在处理");
        }

        try {
            List<ApprovalRequestRecord> records = new ArrayList<ApprovalRequestRecord>();
            // 上架/使用分表查询：一张表字段异常不应拖垮另一张表的回调处理。
            records.addAll(findRequestRecordsSafely(RequestType.ONBOARDING, source, instanceId, businessUniqueKey, force));
            records.addAll(findRequestRecordsSafely(RequestType.USE, source, instanceId, businessUniqueKey, force));
            if (records.isEmpty()) {
                return skipped(force
                        ? "未找到可重放的申请记录"
                        : "未找到审批中的申请，可能已处理；若需续写请使用 force=true");
            }
            if (records.size() > 1) {
                throw new IllegalStateException("同一审批标识匹配到多条申请，instanceId=" + instanceId
                        + ", bizUniqueKey=" + businessUniqueKey);
            }

            ApprovalRequestRecord record = records.get(0);
            log.info("开始处理审批结果, force={}, {}, status={}",
                    force, requestIdentity(record), snapshot.getStatus());
            if (state == ApprovalState.APPROVED) {
                handleApproved(record, snapshot);
            } else {
                handleNonApproved(record, snapshot, state);
            }

            ApprovalHandlingResult result = new ApprovalHandlingResult();
            result.setProcessed(true);
            result.setMessage(force ? "审批结果重放完成" : "审批结果处理完成");
            result.setRequestType(record.getType().name());
            result.setRequestRecordId(record.getRecordId());
            return result;
        } finally {
            releaseLock(lockKey, lockValue);
        }
    }

    private void handleApproved(ApprovalRequestRecord record, ApprovalStatusSnapshot snapshot) {
        log.info("开始处理审批通过业务, {}, status={}", requestIdentity(record), snapshot.getStatus());
        if (record.getType() == RequestType.ONBOARDING) {
            publishApplication(record.getApplicationId());
            grantOnboardingPermissions(record);
            grantApplicationConstructionPoints(record);
            createNotification(record, "应用上架审批通过", "您的应用上架申请已通过审批，应用已上架。");
            updateRequest(record, FeishuConstants.APPROVED_STATUS,
                    nodeOr(snapshot.getCurrentNode(), FeishuConstants.APPLICATION_PUBLISHED_STATUS), null);
        } else {
            // 权限与积分各自幂等：权限按应用+账号查重，积分按申请上的发放记录续写。
            grantPermissionIfMissing(record.getApplicationId(), record.getApplicantId());
            grantApplicationUsePoints(record);
            createNotification(record, "应用使用申请已通过", "您的应用使用申请已通过，已获得应用使用权限。");
            updateRequest(record, FeishuConstants.APPROVED_STATUS,
                    nodeOr(snapshot.getCurrentNode(), FeishuConstants.APPROVED_STATUS), null);
        }
        log.info("审批通过业务处理完成, {}", requestIdentity(record));
    }

    private void handleNonApproved(ApprovalRequestRecord record,
                                   ApprovalStatusSnapshot snapshot, ApprovalState state) {
        String status = state == ApprovalState.REJECTED
                ? FeishuConstants.REJECTED_STATUS : FeishuConstants.CANCELED_STATUS;
        String node = nodeOr(snapshot.getCurrentNode(), status);
        String reason = state == ApprovalState.REJECTED ? snapshot.getRejectionReason() : null;

        if (state == ApprovalState.REJECTED) {
            String title = record.getType() == RequestType.ONBOARDING ? "应用上架审批被拒绝" : "应用使用申请被拒绝";
            String content = StringUtils.hasText(reason)
                    ? "您的申请未通过审批，退回原因：" + reason
                    : "您的申请未通过审批。";
            createNotification(record, title, content);
        }
        updateRequest(record, status, node, reason);
    }

    private void publishApplication(String applicationId) {
        if (!StringUtils.hasText(applicationId)) {
            throw new IllegalStateException("上架申请缺少关联应用ID，无法更新应用索引");
        }
        FeishuProperties.ApprovalPolling config = config();
        List<FeishuRecordSearchVO.RecordItem> records = searchAll(
                config.getApplicationIndexTableId(),
                Arrays.asList(FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD),
                equalFilter(FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD, applicationId));
        if (records.isEmpty()) {
            throw new IllegalStateException("应用索引不存在关联应用ID: " + applicationId);
        }
        if (records.size() > 1) {
            throw new IllegalStateException("应用索引存在重复应用ID: " + applicationId);
        }

        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put(FeishuConstants.APPLICATION_INDEX_STATUS_FIELD, FeishuConstants.APPLICATION_PUBLISHED_STATUS);
        fields.put(FeishuConstants.APPLICATION_INDEX_PUBLISH_TIME_FIELD, now());
        updateRecord(config.getApplicationIndexTableId(), records.get(0).getRecordId(), fields);
    }

    private void grantOnboardingPermissions(ApprovalRequestRecord record) {
        Set<String> userIds = new LinkedHashSet<String>();
        userIds.addAll(idSet(record.getFields().get(FeishuConstants.AUTHORIZED_USER_FIELD)));
        Set<String> departmentIds = idSet(record.getFields().get(FeishuConstants.AUTHORIZED_DEPARTMENT_FIELD));
        if (!departmentIds.isEmpty()) {
            List<FeishuRecordSearchVO.RecordItem> users = searchAll(
                    config().getUserDictionaryTableId(),
                    Arrays.asList(FeishuConstants.DICTIONARY_DEPARTMENT_FIELD,
                            FeishuConstants.DICTIONARY_USER_ID_FIELD), null);
            for (FeishuRecordSearchVO.RecordItem user : users) {
                Map<String, Object> fields = safeFields(user);
                Set<String> userDepartments = idSet(fields.get(FeishuConstants.DICTIONARY_DEPARTMENT_FIELD));
                if (!Collections.disjoint(departmentIds, userDepartments)) {
                    userIds.addAll(idSet(fields.get(FeishuConstants.DICTIONARY_USER_ID_FIELD)));
                }
            }
        }

        for (String userId : userIds) {
            grantPermissionIfMissing(record.getApplicationId(), userId);
        }
    }

    /**
     * 应用首次上架通过后，按角色发放应用建设积分。
     * 同一用户可同时属于多个角色，因此每个角色均单独累计积分。
     */
    private void grantApplicationConstructionPoints(ApprovalRequestRecord record) {
        grantApplicationRolePoints(record, "上架申请人", ONBOARDING_APPLICANT_POINTS,
                APPLICATION_OWNER_POINTS, APPLICATION_DEVELOPER_POINTS, PointBalanceCategory.APPLICATION_BUILD);
    }

    /** 使用申请首次授权成功后，按角色发放应用使用积分。 */
    private void grantApplicationUsePoints(ApprovalRequestRecord record) {
        grantApplicationRolePoints(record, "申请使用人", APPLICATION_USE_APPLICANT_POINTS,
                APPLICATION_USE_OWNER_POINTS, APPLICATION_USE_DEVELOPER_POINTS, PointBalanceCategory.APPLICATION_USE);
    }

    private void grantApplicationRolePoints(ApprovalRequestRecord record, String applicantRole, int applicantPoints,
                                            int ownerPoints, int developerPoints, PointBalanceCategory category) {
        if (!StringUtils.hasText(record.getApplicationId())) {
            throw new IllegalStateException("申请缺少关联应用ID，无法发放积分");
        }
        if (!StringUtils.hasText(record.getApplicantId())) {
            throw new IllegalStateException("申请缺少申请人ID，无法发放积分");
        }
        FeishuProperties.ApprovalPolling config = config();
        List<FeishuRecordSearchVO.RecordItem> applications = searchAll(
                config.getApplicationIndexTableId(),
                Arrays.asList(FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD,
                        FeishuConstants.APPLICATION_INDEX_OWNER_FIELD,
                        FeishuConstants.APPLICATION_INDEX_DEVELOPER_FIELD),
                equalFilter(FeishuConstants.APPLICATION_INDEX_APPLICATION_ID_FIELD, record.getApplicationId()));
        if (applications.isEmpty()) {
            throw new IllegalStateException("应用索引不存在关联应用ID，无法发放积分: " + record.getApplicationId());
        }
        if (applications.size() > 1) {
            throw new IllegalStateException("应用索引存在重复应用ID，无法发放积分: " + record.getApplicationId());
        }

        Map<String, Object> applicationFields = safeFields(applications.get(0));
        List<PointAward> awards = new ArrayList<PointAward>();
        addPointAwards(awards, idSet(applicationFields.get(FeishuConstants.APPLICATION_INDEX_OWNER_FIELD)),
                "应用负责人", ownerPoints, category);
        addPointAwards(awards, idSet(applicationFields.get(FeishuConstants.APPLICATION_INDEX_DEVELOPER_FIELD)),
                "应用开发者", developerPoints, category);
        addPointAwards(awards, idSet(record.getApplicantId()), applicantRole, applicantPoints, category);
        if (awards.isEmpty()) {
            throw new IllegalStateException("申请积分角色为空，无法发放积分");
        }
        String categoryLabel = category == PointBalanceCategory.APPLICATION_USE ? "应用使用" : "应用建设";
        log.info("开始发放积分, {}, category={}, plannedCount={}",
                requestIdentity(record), categoryLabel, awards.size());

        // 余额读改写与发放记录追加在同一把锁内完成，避免并发审批重复加分。
        withBusinessKeyLock("POINT", new LockedOperation() {
            @Override
            public void execute() {
                Set<String> awardedKeys = parsePointAwardKeys(
                        text(record.getFields().get(FeishuConstants.POINTS_AWARD_RECORD_FIELD)));
                int grantedCount = 0;
                for (PointAward award : awards) {
                    String awardKey = pointAwardKey(award);
                    if (awardedKeys.contains(awardKey)) {
                        log.info("积分已发放过，跳过, {}, awardKey={}", requestIdentity(record), awardKey);
                        continue;
                    }
                    grantPointAward(record, award);
                    awardedKeys.add(awardKey);
                    appendPointAwardRecord(record, awardedKeys);
                    grantedCount++;
                }
                log.info("积分发放结束, {}, category={}, grantedCount={}, skippedCount={}",
                        requestIdentity(record), categoryLabel, grantedCount, awards.size() - grantedCount);
            }
        });
    }

    private Set<String> parsePointAwardKeys(String raw) {
        Set<String> keys = new LinkedHashSet<String>();
        if (!StringUtils.hasText(raw)) {
            return keys;
        }
        for (String item : raw.split("[;；\\n\\r]+")) {
            if (StringUtils.hasText(item)) {
                keys.add(item.trim());
            }
        }
        return keys;
    }

    private String pointAwardKey(PointAward award) {
        String category = award.getCategory() == PointBalanceCategory.APPLICATION_USE
                ? "应用使用" : "应用建设";
        return award.getUserId() + "|" + award.getRole() + "|" + category;
    }

    private void appendPointAwardRecord(ApprovalRequestRecord record, Set<String> awardedKeys) {
        StringBuilder builder = new StringBuilder();
        for (String key : awardedKeys) {
            if (builder.length() > 0) {
                builder.append('；');
            }
            builder.append(key);
        }
        String value = builder.toString();
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put(FeishuConstants.POINTS_AWARD_RECORD_FIELD, value);
        updateRecord(record.getTableId(), record.getRecordId(), fields);
        record.getFields().put(FeishuConstants.POINTS_AWARD_RECORD_FIELD, value);
    }

    private void addPointAwards(List<PointAward> awards, Set<String> userIds, String role, int points,
                                PointBalanceCategory category) {
        for (String userId : userIds) {
            if (StringUtils.hasText(userId)) {
                awards.add(new PointAward(userId.trim(), role, points, category));
            }
        }
    }

    private void grantPointAward(ApprovalRequestRecord record, PointAward award) {
        FeishuProperties.ApprovalPolling config = config();
        String categoryField = award.getCategory() == PointBalanceCategory.APPLICATION_USE
                ? FeishuConstants.POINTS_BALANCE_APPLICATION_USE_FIELD
                : FeishuConstants.POINTS_BALANCE_APPLICATION_BUILD_FIELD;
        List<FeishuRecordSearchVO.RecordItem> balances = searchAll(
                config.getPointsBalanceTableId(),
                Arrays.asList(FeishuConstants.POINTS_BALANCE_KEY_FIELD,
                        FeishuConstants.POINTS_BALANCE_TOTAL_FIELD, categoryField,
                        FeishuConstants.POINTS_BALANCE_MONTH_FIELD,
                        FeishuConstants.POINTS_BALANCE_UPDATED_AT_FIELD),
                equalFilter(FeishuConstants.POINTS_BALANCE_USER_ID_FIELD, award.getUserId()));
        if (balances.size() > 1) {
            throw new IllegalStateException("积分余额存在重复用户记录，userId=" + award.getUserId());
        }

        long totalBefore = 0L;
        long categoryBefore = 0L;
        long monthBefore = 0L;
        String lastUpdatedAt = "";
        if (!balances.isEmpty()) {
            Map<String, Object> fields = safeFields(balances.get(0));
            totalBefore = numberValue(fields.get(FeishuConstants.POINTS_BALANCE_TOTAL_FIELD));
            categoryBefore = numberValue(fields.get(categoryField));
            monthBefore = numberValue(fields.get(FeishuConstants.POINTS_BALANCE_MONTH_FIELD));
            lastUpdatedAt = text(fields.get(FeishuConstants.POINTS_BALANCE_UPDATED_AT_FIELD));
        }
        LocalDateTime pointTime = LocalDateTime.now();
        String currentMonth = pointTime.format(MONTH_FORMATTER);
        boolean isNewMonth = StringUtils.hasText(lastUpdatedAt) && !lastUpdatedAt.startsWith(currentMonth);
        long totalAfter = totalBefore + award.getPoints();
        long categoryAfter = categoryBefore + award.getPoints();
        long monthAfter = isNewMonth ? award.getPoints() : monthBefore + award.getPoints();
        String occurredAt = pointTime.format(DATE_TIME_FORMATTER);

        Map<String, Object> balanceFields = new LinkedHashMap<String, Object>();
        balanceFields.put(FeishuConstants.POINTS_BALANCE_TOTAL_FIELD, totalAfter);
        balanceFields.put(categoryField, categoryAfter);
        balanceFields.put(FeishuConstants.POINTS_BALANCE_MONTH_FIELD, monthAfter);
        if (isNewMonth) {
            balanceFields.put(FeishuConstants.POINTS_BALANCE_LAST_MONTH_FIELD, monthBefore);
        }
        balanceFields.put(FeishuConstants.POINTS_BALANCE_UPDATED_AT_FIELD, occurredAt);
        if (balances.isEmpty()) {
            balanceFields.put(FeishuConstants.POINTS_BALANCE_KEY_FIELD, nextSequentialBusinessKey("PB",
                    config.getPointsBalanceTableId(), FeishuConstants.POINTS_BALANCE_KEY_FIELD, 3));
            balanceFields.put(FeishuConstants.POINTS_BALANCE_USER_ID_FIELD, award.getUserId());
            createRecord(config.getPointsBalanceTableId(), balanceFields);
            log.info("积分余额新建并加分, {}, userId={}, role={}, category={}, 本次加分={}, 总积分之前={}, 总积分之后={}",
                    requestIdentity(record), award.getUserId(), award.getRole(), categoryField,
                    award.getPoints(), totalBefore, totalAfter);
        } else {
            updateRecord(config.getPointsBalanceTableId(), balances.get(0).getRecordId(), balanceFields);
            log.info("积分余额加分, {}, userId={}, role={}, category={}, 本次加分={}, 总积分之前={}, 总积分之后={}, {}之前={}, {}之后={}, 本月积分之前={}, 本月积分之后={}",
                    requestIdentity(record), award.getUserId(), award.getRole(), categoryField, award.getPoints(),
                    totalBefore, totalAfter, categoryField, categoryBefore, categoryField, categoryAfter,
                    monthBefore, monthAfter);
        }
    }

    /**
     * 日志关联键：唯一标识 + 申请单号/主键 + 应用ID + 审批实例ID，便于对照飞书表。
     */
    private String requestIdentity(ApprovalRequestRecord record) {
        String applicationNoField = record.getType() == RequestType.ONBOARDING
                ? FeishuConstants.APPLICATION_NO_FIELD : FeishuConstants.USE_APPLICATION_NO_FIELD;
        String applicationNo = record.getFields() == null
                ? "" : text(record.getFields().get(applicationNoField));
        return "uniqueIdentifier=" + nullToEmpty(record.getBusinessUniqueKey())
                + ", applicationNo=" + nullToEmpty(applicationNo)
                + ", applicationId=" + nullToEmpty(record.getApplicationId())
                + ", instId=" + nullToEmpty(record.getApprovalInstanceId())
                + ", requestRecordId=" + nullToEmpty(record.getRecordId());
    }

    private String nullToEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    /**
     * 按应用ID + AD账号查用户权限表：已有授权则跳过；没有才新增。
     * 返回 true 表示本次新写入权限，调用方据此决定是否发放使用积分。
     */
    private boolean grantPermissionIfMissing(String applicationId, String userId) {
        if (!StringUtils.hasText(applicationId) || !StringUtils.hasText(userId)) {
            throw new IllegalStateException("授权缺少应用ID或AD账号");
        }
        FeishuRecordSearchRequest.FilterInfo filter = conjunctionFilter(Arrays.asList(
                condition(FeishuConstants.PERMISSION_APPLICATION_ID_FIELD, "is", applicationId.trim()),
                condition(FeishuConstants.PERMISSION_USER_ID_FIELD, "is", userId.trim())));
        List<FeishuRecordSearchVO.RecordItem> existed = searchAll(
                config().getUserPermissionTableId(),
                Collections.singletonList(FeishuConstants.PERMISSION_KEY_FIELD), filter);
        if (!existed.isEmpty()) {
            return false;
        }

        AtomicBoolean created = new AtomicBoolean(false);
        withBusinessKeyLock("AUA", new LockedOperation() {
            @Override
            public void execute() {
                // 获取编号锁后再次确认，避免并发回调为同一用户重复授权。
                if (!searchAll(config().getUserPermissionTableId(),
                        Collections.singletonList(FeishuConstants.PERMISSION_KEY_FIELD), filter).isEmpty()) {
                    return;
                }
                Map<String, Object> fields = new LinkedHashMap<String, Object>();
                fields.put(FeishuConstants.PERMISSION_KEY_FIELD, nextSequentialBusinessKey("AUA",
                        config().getUserPermissionTableId(), FeishuConstants.PERMISSION_KEY_FIELD, 3));
                fields.put(FeishuConstants.PERMISSION_APPLICATION_ID_FIELD, applicationId.trim());
                fields.put(FeishuConstants.PERMISSION_USER_ID_FIELD, userId.trim());
                fields.put(FeishuConstants.PERMISSION_TIME_FIELD, now());
                fields.put(FeishuConstants.PERMISSION_STATUS_FIELD, FeishuConstants.PERMISSION_GRANTED_STATUS);
                createRecord(config().getUserPermissionTableId(), fields);
                created.set(true);
            }
        });
        return created.get();
    }

    private void createNotification(ApprovalRequestRecord record, String title, String content) {
        if (!StringUtils.hasText(record.getApplicantId())) {
            throw new IllegalStateException("申请记录缺少申请人ID，无法发送通知");
        }
        final String uniqueKey = resolveNotificationUniqueKey(record);
        if (!StringUtils.hasText(uniqueKey)) {
            throw new IllegalStateException("申请缺少唯一标识/申请编号/审批实例ID，无法发送通知");
        }
        final FeishuProperties.ApprovalPolling config = config();
        withBusinessKeyLock("MSG", new LockedOperation() {
            @Override
            public void execute() {
                List<FeishuRecordSearchVO.RecordItem> existed = searchAll(
                        config.getNotificationTableId(),
                        Arrays.asList(FeishuConstants.UNIQUE_IDENTIFIER_FIELD,
                                FeishuConstants.NOTIFICATION_KEY_FIELD),
                        equalFilter(FeishuConstants.UNIQUE_IDENTIFIER_FIELD, uniqueKey));
                if (!existed.isEmpty()) {
                    log.info("通知已存在，跳过创建, uniqueKey={}, notificationCount={}",
                            uniqueKey, existed.size());
                    return;
                }
                Map<String, Object> fields = new LinkedHashMap<String, Object>();
                fields.put(FeishuConstants.NOTIFICATION_KEY_FIELD, nextSequentialBusinessKey("MSG",
                        config.getNotificationTableId(), FeishuConstants.NOTIFICATION_KEY_FIELD));
                fields.put(FeishuConstants.UNIQUE_IDENTIFIER_FIELD, uniqueKey);
                fields.put(FeishuConstants.NOTIFICATION_RECEIVER_FIELD, record.getApplicantId());
                fields.put(FeishuConstants.NOTIFICATION_TITLE_FIELD, title);
                fields.put(FeishuConstants.NOTIFICATION_CONTENT_FIELD, content);
                fields.put(FeishuConstants.NOTIFICATION_CATEGORY_FIELD, FeishuConstants.NOTIFICATION_CATEGORY_VALUE);
                fields.put(FeishuConstants.NOTIFICATION_UNREAD_FIELD, FeishuConstants.NOTIFICATION_UNREAD_VALUE);
                fields.put(FeishuConstants.NOTIFICATION_TIME_FIELD, now());
                createRecord(config.getNotificationTableId(), fields);
            }
        });
    }

    /**
     * 通知幂等键：上架用唯一标识，使用申请用申请编号，都缺失时退回审批实例ID。
     */
    private String resolveNotificationUniqueKey(ApprovalRequestRecord record) {
        if (record.getType() == RequestType.ONBOARDING && StringUtils.hasText(record.getBusinessUniqueKey())) {
            return record.getBusinessUniqueKey().trim();
        }
        String applicationNo = text(record.getFields().get(record.getType() == RequestType.ONBOARDING
                ? FeishuConstants.APPLICATION_NO_FIELD : FeishuConstants.USE_APPLICATION_NO_FIELD));
        if (StringUtils.hasText(applicationNo)) {
            return applicationNo;
        }
        if (StringUtils.hasText(record.getApprovalInstanceId())) {
            return record.getApprovalInstanceId().trim();
        }
        return "";
    }

    private void updateRequest(ApprovalRequestRecord record, String status, String currentNode, String rejectionReason) {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put(FeishuConstants.STATUS_FIELD, status);
        // 使用申请表没有「当前审批节点」「退回原因」，仅上架申请回写这两列。
        if (record.getType() == RequestType.ONBOARDING) {
            if (StringUtils.hasText(currentNode)) {
                fields.put(FeishuConstants.CURRENT_NODE_FIELD, currentNode);
            }
            if (StringUtils.hasText(rejectionReason)) {
                fields.put(FeishuConstants.REJECTION_REASON_FIELD, rejectionReason);
            }
        }
        updateRecord(record.getTableId(), record.getRecordId(), fields);
    }

    private List<ApprovalRequestRecord> findPendingRecordsSafely(RequestType type, String source, String instanceId,
                                                                  String businessUniqueKey) {
        return findRequestRecordsSafely(type, source, instanceId, businessUniqueKey, false);
    }

    private List<ApprovalRequestRecord> findRequestRecordsSafely(RequestType type, String source, String instanceId,
                                                                 String businessUniqueKey, boolean force) {
        try {
            return findRequestRecords(type, source, instanceId, businessUniqueKey, force);
        } catch (RuntimeException ex) {
            log.error("查询申请记录失败，已跳过该表继续处理, type={}, source={}, instanceId={}, bizUniqueKey={}, force={}, reason={}",
                    type, source, instanceId, businessUniqueKey, force, ex.getMessage(), ex);
            return Collections.emptyList();
        }
    }

    private List<ApprovalRequestRecord> findPendingRecords(RequestType type, String source, String instanceId,
                                                           String businessUniqueKey) {
        return findRequestRecords(type, source, instanceId, businessUniqueKey, false);
    }

    private List<ApprovalRequestRecord> findRequestRecords(RequestType type, String source, String instanceId,
                                                           String businessUniqueKey, boolean force) {
        List<ApprovalRequestRecord> records = new ArrayList<ApprovalRequestRecord>();
        List<FeishuRecordSearchRequest.Condition> conditions = new ArrayList<FeishuRecordSearchRequest.Condition>();
        if (!force) {
            conditions.add(condition(FeishuConstants.STATUS_FIELD, "is", FeishuConstants.PENDING_STATUS));
        }
        conditions.add(condition(FeishuConstants.SOURCE_FIELD, "is", source));
        if (type == RequestType.ONBOARDING && StringUtils.hasText(businessUniqueKey)) {
            conditions.add(condition(FeishuConstants.UNIQUE_IDENTIFIER_FIELD, "is", businessUniqueKey));
        } else if (type == RequestType.USE && StringUtils.hasText(businessUniqueKey)
                && !StringUtils.hasText(instanceId)) {
            // 使用申请也可用唯一标识精确匹配（有该字段时）。
            conditions.add(condition(FeishuConstants.UNIQUE_IDENTIFIER_FIELD, "is", businessUniqueKey));
        } else if (StringUtils.hasText(instanceId)) {
            conditions.add(condition(FeishuConstants.APPROVAL_INSTANCE_FIELD, "is", instanceId));
        } else if (force) {
            // 强制重放必须精确命中，禁止退化成「任意有审批实例ID」的宽查。
            log.info("强制重放缺少可用匹配键，跳过该表, type={}, source={}", type, source);
            return records;
        } else {
            conditions.add(condition(FeishuConstants.APPROVAL_INSTANCE_FIELD, "isNotEmpty", null));
        }

        List<FeishuRecordSearchVO.RecordItem> items = searchAll(
                tableIdFor(type), fieldsFor(type), conjunctionFilter(conditions));
        for (FeishuRecordSearchVO.RecordItem item : items) {
            Map<String, Object> fields = safeFields(item);
            String approvalInstanceId = text(fields.get(FeishuConstants.APPROVAL_INSTANCE_FIELD));
            String recordBusinessUniqueKey = text(fields.get(FeishuConstants.UNIQUE_IDENTIFIER_FIELD));
            if (!StringUtils.hasText(approvalInstanceId) && !StringUtils.hasText(recordBusinessUniqueKey)) {
                continue;
            }
            ApprovalRequestRecord record = new ApprovalRequestRecord();
            record.setType(type);
            record.setTableId(tableIdFor(type));
            record.setRecordId(item.getRecordId());
            record.setApprovalInstanceId(approvalInstanceId);
            record.setBusinessUniqueKey(recordBusinessUniqueKey);
            record.setFields(new LinkedHashMap<String, Object>(safeFields(item)));
            record.setApplicationId(text(fields.get(type == RequestType.ONBOARDING
                    ? FeishuConstants.APPLICATION_ID_FIELD : FeishuConstants.USE_APPLICATION_ID_FIELD)));
            record.setApplicantId(text(fields.get(FeishuConstants.APPLICANT_ID_FIELD)));
            records.add(record);
        }
        return records;
    }

    private List<String> fieldsFor(RequestType type) {
        List<String> fields = new ArrayList<String>();
        fields.add(FeishuConstants.APPROVAL_INSTANCE_FIELD);
        if (type == RequestType.ONBOARDING) {
            fields.add(FeishuConstants.UNIQUE_IDENTIFIER_FIELD);
            fields.add(FeishuConstants.APPLICATION_NO_FIELD);
        } else {
            fields.add(FeishuConstants.UNIQUE_IDENTIFIER_FIELD);
            fields.add(FeishuConstants.USE_APPLICATION_NO_FIELD);
        }
        fields.add(FeishuConstants.POINTS_AWARD_RECORD_FIELD);
        fields.add(FeishuConstants.APPLICANT_ID_FIELD);
        fields.add(type == RequestType.ONBOARDING
                ? FeishuConstants.APPLICATION_ID_FIELD : FeishuConstants.USE_APPLICATION_ID_FIELD);
        if (type == RequestType.ONBOARDING) {
            fields.add(FeishuConstants.AUTHORIZED_DEPARTMENT_FIELD);
            fields.add(FeishuConstants.AUTHORIZED_USER_FIELD);
        }
        return fields;
    }

    private String tableIdFor(RequestType type) {
        return type == RequestType.ONBOARDING ? config().getTableId() : config().getUseTableId();
    }

    private ApprovalStatusSnapshot queryFeishuWithRetry(String approvalInstanceId) {
        Integer configuredRetryCount = config().getQueryRetryCount();
        if (configuredRetryCount == null || configuredRetryCount < 1) {
            throw new IllegalStateException("未配置有效的 feishu.approval-polling.query-retry-count");
        }
        int retryCount = configuredRetryCount;
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= retryCount; attempt++) {
            try {
                return feishuApprovalService.queryInstance(approvalInstanceId);
            } catch (RuntimeException ex) {
                lastError = ex;
                log.warn("飞书审批实例查询失败，instanceId={}, attempt={}/{}，原因={}",
                        approvalInstanceId, attempt, retryCount, ex.getMessage());
                if (attempt < retryCount) {
                    sleepBeforeRetry(attempt);
                }
            }
        }
        throw lastError == null ? new IllegalStateException("飞书审批实例查询失败") : lastError;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(300L * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("飞书审批实例查询重试被中断", ex);
        }
    }

    private List<FeishuRecordSearchVO.RecordItem> searchAll(String tableId, List<String> fieldNames,
                                                             FeishuRecordSearchRequest.FilterInfo filter) {
        FeishuProperties.ApprovalPolling config = config();
        List<FeishuRecordSearchVO.RecordItem> all = new ArrayList<FeishuRecordSearchVO.RecordItem>();
        String pageToken = null;
        do {
            FeishuRecordSearchRequest request = new FeishuRecordSearchRequest();
            request.setAppToken(config.getAppToken());
            request.setTableId(tableId);
            request.setPageSize(500);
            request.setPageToken(pageToken);
            request.setFieldNames(fieldNames);
            request.setAutomaticFields(Boolean.FALSE);
            request.setFilter(filter);
            FeishuRecordSearchVO response = feishuBitableService.searchRecords(request);
            if (response == null) {
                throw new IllegalStateException("查询飞书多维表返回为空, tableId=" + tableId);
            }
            if (response.getItems() != null) {
                all.addAll(response.getItems());
            }
            if (Boolean.TRUE.equals(response.getHasMore()) && StringUtils.hasText(response.getPageToken())) {
                pageToken = response.getPageToken();
            } else {
                pageToken = null;
            }
        } while (StringUtils.hasText(pageToken));
        return all;
    }

    private FeishuRecordSearchRequest.FilterInfo equalFilter(String fieldName, String value) {
        return conjunctionFilter(Collections.singletonList(condition(fieldName, "is", value)));
    }

    private FeishuRecordSearchRequest.FilterInfo conjunctionFilter(List<FeishuRecordSearchRequest.Condition> conditions) {
        FeishuRecordSearchRequest.FilterInfo filter = new FeishuRecordSearchRequest.FilterInfo();
        filter.setConjunction("and");
        filter.setConditions(conditions);
        return filter;
    }

    private FeishuRecordSearchRequest.Condition condition(String fieldName, String operator, String value) {
        FeishuRecordSearchRequest.Condition condition = new FeishuRecordSearchRequest.Condition();
        condition.setFieldName(fieldName);
        condition.setOperator(operator);
        condition.setValue(value == null ? Collections.<String>emptyList() : Collections.singletonList(value));
        return condition;
    }

    private void createRecord(String tableId, Map<String, Object> fields) {
        FeishuRecordCreateRequest request = new FeishuRecordCreateRequest();
        request.setAppToken(config().getAppToken());
        request.setTableId(tableId);
        request.setFields(fields);
        feishuBitableService.createRecord(request);
    }

    private void updateRecord(String tableId, String recordId, Map<String, Object> fields) {
        FeishuRecordUpdateRequest request = new FeishuRecordUpdateRequest();
        request.setAppToken(config().getAppToken());
        request.setTableId(tableId);
        request.setRecordId(recordId);
        request.setFields(fields);
        feishuBitableService.updateRecord(request);
    }

    private boolean tryLock(String lockKey, String lockValue) {
        Long timeout = config().getLockTimeoutSeconds();
        if (timeout == null || timeout < 1) {
            throw new IllegalStateException("未配置有效的 feishu.approval-polling.lock-timeout-seconds");
        }
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey, lockValue, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    private void releaseLock(String lockKey, String lockValue) {
        try {
            stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (Exception ex) {
            log.warn("释放审批处理锁失败，lockKey={}", lockKey, ex);
        }
    }

    private void validatePollingConfiguration() {
        FeishuProperties.ApprovalPolling config = config();
        requireConfig("feishu.approval-polling.app-token", config.getAppToken());
        requireConfig("feishu.approval-polling.table-id", config.getTableId());
        requireConfig("feishu.approval-polling.use-table-id", config.getUseTableId());
        requireConfig("feishu.approval-polling.application-index-table-id", config.getApplicationIndexTableId());
        requireConfig("feishu.approval-polling.user-dictionary-table-id", config.getUserDictionaryTableId());
        requireConfig("feishu.approval-polling.user-permission-table-id", config.getUserPermissionTableId());
        requireConfig("feishu.approval-polling.notification-table-id", config.getNotificationTableId());
        requireConfig("feishu.approval-polling.points-balance-table-id", config.getPointsBalanceTableId());
        if (config.getQueryRetryCount() == null || config.getQueryRetryCount() < 1) {
            throw new IllegalStateException("未配置有效的 feishu.approval-polling.query-retry-count");
        }
        if (config.getLockTimeoutSeconds() == null || config.getLockTimeoutSeconds() < 1) {
            throw new IllegalStateException("未配置有效的 feishu.approval-polling.lock-timeout-seconds");
        }
    }

    private void requireConfig(String name, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("未配置 " + name);
        }
    }

    private FeishuProperties.ApprovalPolling config() {
        return feishuProperties.getApprovalPolling();
    }

    private ApprovalHandlingResult skipped(String message) {
        ApprovalHandlingResult result = new ApprovalHandlingResult();
        result.setProcessed(false);
        result.setMessage(message);
        return result;
    }

    private String nodeOr(String callbackNode, String defaultNode) {
        return StringUtils.hasText(callbackNode) ? callbackNode.trim() : defaultNode;
    }

    private String now() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }

    private String nextSequentialBusinessKey(String prefix, String tableId, String fieldName) {
        return nextSequentialBusinessKey(prefix, tableId, fieldName, 4);
    }

    private String nextSequentialBusinessKey(String prefix, String tableId, String fieldName, int digits) {
        if (digits < 1) {
            throw new IllegalArgumentException("编号位数必须大于 0");
        }
        int max = 0;
        for (FeishuRecordSearchVO.RecordItem item : searchAll(tableId,
                Collections.singletonList(fieldName), null)) {
            String value = text(safeFields(item).get(fieldName));
            if (!StringUtils.hasText(value) || !value.startsWith(prefix)) {
                continue;
            }
            String suffix = value.substring(prefix.length());
            if (suffix.matches("\\d+")) {
                max = Math.max(max, Integer.parseInt(suffix));
            }
        }
        int limit = (int) Math.pow(10, digits) - 1;
        if (max >= limit) {
            throw new IllegalStateException(prefix + "编号已达到 " + prefix
                    + String.format("%0" + digits + "d", limit) + "，无法继续生成");
        }
        return prefix + String.format("%0" + digits + "d", max + 1);
    }

    private void withBusinessKeyLock(String prefix, LockedOperation operation) {
        String lockKey = LOCK_PREFIX + "business-key:" + prefix;
        String lockValue = UUID.randomUUID().toString();
        if (!tryLock(lockKey, lockValue)) {
            throw new IllegalStateException(prefix + "编号正在生成，请稍后重试");
        }
        try {
            operation.execute();
        } finally {
            releaseLock(lockKey, lockValue);
        }
    }

    private Map<String, Object> safeFields(FeishuRecordSearchVO.RecordItem item) {
        return item == null || item.getFields() == null
                ? Collections.<String, Object>emptyMap()
                : item.getFields();
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                String text = text(item);
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
            return "";
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (String key : Arrays.asList("text", "name", "value", "id", "user_id", "open_id")) {
                if (map.containsKey(key)) {
                    String text = text(map.get(key));
                    if (StringUtils.hasText(text)) {
                        return text;
                    }
                }
            }
            return "";
        }
        return String.valueOf(value).trim();
    }

    private long numberValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String valueText = text(value).replace(",", "");
        if (!StringUtils.hasText(valueText)) {
            return 0L;
        }
        try {
            return new java.math.BigDecimal(valueText).longValue();
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("积分余额字段不是有效数字: " + valueText, ex);
        }
    }

    private Set<String> idSet(Object value) {
        Set<String> values = new LinkedHashSet<String>();
        collectIds(value, values);
        return values;
    }

    private void collectIds(Object value, Set<String> values) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                collectIds(item, values);
            }
            return;
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (String key : Arrays.asList("user_id", "open_id", "id", "value", "text", "name")) {
                if (map.containsKey(key)) {
                    collectIds(map.get(key), values);
                    return;
                }
            }
            return;
        }
        String raw = String.valueOf(value).trim();
        if (!StringUtils.hasText(raw)) {
            return;
        }
        for (String item : raw.split("[,，;；\\n\\r]+")) {
            if (StringUtils.hasText(item)) {
                values.add(item.trim());
            }
        }
    }

    private enum RequestType {
        ONBOARDING,
        USE
    }

    private interface LockedOperation {
        void execute();
    }

    private static class PointAward {

        private final String userId;
        private final String role;
        private final int points;
        private final PointBalanceCategory category;

        private PointAward(String userId, String role, int points, PointBalanceCategory category) {
            this.userId = userId;
            this.role = role;
            this.points = points;
            this.category = category;
        }

        private String getUserId() {
            return userId;
        }

        private String getRole() {
            return role;
        }

        private int getPoints() {
            return points;
        }

        private PointBalanceCategory getCategory() {
            return category;
        }
    }

    private enum PointBalanceCategory {
        APPLICATION_BUILD,
        APPLICATION_USE
    }

    private enum ApprovalState {
        PENDING,
        APPROVED,
        REJECTED,
        CANCELED,
        UNKNOWN;

        private static ApprovalState from(String value) {
            String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            // EAD 回调常见中文态：流转中=进行中，结束=审批完成（通过）。
            if (Arrays.asList("PENDING", "IN_PROGRESS", "PROCESSING", "审批中", "待审批", "流转中").contains(normalized)) {
                return PENDING;
            }
            if (Arrays.asList("APPROVED", "PASSED", "PASS", "已通过", "结束", "已结束", "完成", "已完成").contains(normalized)) {
                return APPROVED;
            }
            if (Arrays.asList("REJECTED", "REFUSED", "DENIED", "已拒绝", "已退回").contains(normalized)) {
                return REJECTED;
            }
            if (Arrays.asList("CANCELED", "CANCELLED", "WITHDRAWN", "已撤回").contains(normalized)) {
                return CANCELED;
            }
            return UNKNOWN;
        }
    }

    private static class ApprovalRequestRecord {

        private RequestType type;
        private String tableId;
        private String recordId;
        private String approvalInstanceId;
        private String businessUniqueKey;
        private String applicationId;
        private String applicantId;
        private Map<String, Object> fields;

        private RequestType getType() {
            return type;
        }

        private void setType(RequestType type) {
            this.type = type;
        }

        private String getTableId() {
            return tableId;
        }

        private void setTableId(String tableId) {
            this.tableId = tableId;
        }

        private String getRecordId() {
            return recordId;
        }

        private void setRecordId(String recordId) {
            this.recordId = recordId;
        }

        private String getApprovalInstanceId() {
            return approvalInstanceId;
        }

        private void setApprovalInstanceId(String approvalInstanceId) {
            this.approvalInstanceId = approvalInstanceId;
        }

        private String getBusinessUniqueKey() {
            return businessUniqueKey;
        }

        private void setBusinessUniqueKey(String businessUniqueKey) {
            this.businessUniqueKey = businessUniqueKey;
        }

        private String getApplicationId() {
            return applicationId;
        }

        private void setApplicationId(String applicationId) {
            this.applicationId = applicationId;
        }

        private String getApplicantId() {
            return applicantId;
        }

        private void setApplicantId(String applicantId) {
            this.applicantId = applicantId;
        }

        private Map<String, Object> getFields() {
            return fields;
        }

        private void setFields(Map<String, Object> fields) {
            this.fields = fields;
        }
    }
}
