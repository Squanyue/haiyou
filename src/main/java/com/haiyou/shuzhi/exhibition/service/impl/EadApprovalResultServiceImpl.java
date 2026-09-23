package com.haiyou.shuzhi.exhibition.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haiyou.shuzhi.exhibition.common.FeishuConstants;
import com.haiyou.shuzhi.exhibition.dto.ApprovalHandlingResult;
import com.haiyou.shuzhi.exhibition.dto.ApprovalStatusSnapshot;
import com.haiyou.shuzhi.exhibition.service.ApprovalResultProcessor;
import com.haiyou.shuzhi.exhibition.service.EadApprovalResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新版 EAD JSON 回调适配器：只标准化回调字段，后续业务交由统一审批处理器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EadApprovalResultServiceImpl implements EadApprovalResultService {

    private final ObjectMapper objectMapper;
    private final ApprovalResultProcessor approvalResultProcessor;

    @Override
    public ApprovalHandlingResult handleResult(Map<String, Object> callbackParams) {
        ApprovalStatusSnapshot snapshot = parseSnapshot(callbackParams);
        log.info("接收新版 EAD 审批结果回调, bizUniqueKey={}, instanceId={}, status={}",
                snapshot.getBusinessUniqueKey(), snapshot.getApprovalInstanceId(), snapshot.getStatus());
        return approvalResultProcessor.process(FeishuConstants.EAD_SOURCE, snapshot);
    }

    private ApprovalStatusSnapshot parseSnapshot(Map<String, Object> callbackParams) {
        if (callbackParams == null || callbackParams.isEmpty()) {
            throw new IllegalArgumentException("EAD 回调参数不能为空");
        }
        List<Map<String, Object>> sources = collectSources(callbackParams);
        ApprovalStatusSnapshot snapshot = new ApprovalStatusSnapshot();
        snapshot.setApprovalInstanceId(firstValue(sources,
                "processInstanceId", "process_instance_id", "approvalInstanceId", "approval_instance_id",
                "instanceId", "instance_id", "instanceCode", "instance_code", "instId", "inst_id"));
        snapshot.setBusinessUniqueKey(firstValue(sources,
                "bizUniqueKey", "businessUniqueKey", "business_unique_key",
                "uniqueIdentifier", "unique_identifier"));
        // EAD 真实回调把流程状态放在 resultString.inst.status（流转中 / 结束 / 中止）。
        snapshot.setStatus(firstValue(sources,
                "approvalStatus", "approval_status", "processStatus", "process_status", "resultStatus", "status"));
        snapshot.setCurrentNode(firstValue(sources,
                "currentNode", "current_node", "currentNodeName", "current_node_name", "nodeName", "node_name"));
        snapshot.setRejectionReason(firstValue(sources,
                "comment", "approvalComment", "approval_comment", "reason", "rejectionReason",
                "rejection_reason", "approveOpinion", "opinion"));
        snapshot.setApprovalTime(firstValue(sources,
                "approvalTime", "approval_time", "completedAt", "completed_at", "completeTime", "complete_time"));
        if (!StringUtils.hasText(snapshot.getApprovalInstanceId())
                && !StringUtils.hasText(snapshot.getBusinessUniqueKey())) {
            throw new IllegalArgumentException("EAD 回调未包含审批实例ID或 bizUniqueKey");
        }
        if (!StringUtils.hasText(snapshot.getStatus())) {
            throw new IllegalArgumentException("EAD 回调未包含审批状态");
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectSources(Map<String, Object> callbackParams) {
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        sources.add(callbackParams);
        addMap(sources, callbackParams.get("data"));
        addMap(sources, callbackParams.get("result"));
        addMap(sources, callbackParams.get("payload"));
        addMap(sources, callbackParams.get("body"));
        Object resultString = callbackParams.get("resultString");
        if (resultString instanceof String && StringUtils.hasText((String) resultString)) {
            try {
                Map<String, Object> parsed = objectMapper.readValue((String) resultString,
                        new TypeReference<Map<String, Object>>() { });
                sources.add(parsed);
                addMap(sources, parsed.get("data"));
                // inst 优先于 bean：流程状态在 inst，表单字段在 bean。
                addNameValueList(sources, parsed.get("inst"));
                addNameValueList(sources, parsed.get("bean"));
            } catch (Exception ex) {
                log.warn("EAD resultString 不是有效 JSON，忽略其结构化解析: {}", ex.getMessage());
            }
        } else if (resultString instanceof Map) {
            Map<String, Object> parsed = (Map<String, Object>) resultString;
            sources.add(parsed);
            addMap(sources, parsed.get("data"));
            addNameValueList(sources, parsed.get("inst"));
            addNameValueList(sources, parsed.get("bean"));
        }
        addNameValueList(sources, callbackParams.get("inst"));
        addNameValueList(sources, callbackParams.get("bean"));
        return sources;
    }

    @SuppressWarnings("unchecked")
    private void addMap(List<Map<String, Object>> sources, Object value) {
        if (value instanceof Map) {
            sources.add((Map<String, Object>) value);
        }
    }

    /**
     * 展开 EAD 的 [{name,value}, ...] 列表（inst / bean）。
     */
    @SuppressWarnings("unchecked")
    private void addNameValueList(List<Map<String, Object>> sources, Object value) {
        if (!(value instanceof Collection)) {
            return;
        }
        Map<String, Object> flat = new LinkedHashMap<String, Object>();
        for (Object item : (Collection<?>) value) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) item;
            String name = text(entry.get("name"));
            if (StringUtils.hasText(name)) {
                flat.put(name, entry.get("value"));
            }
        }
        if (!flat.isEmpty()) {
            sources.add(flat);
        }
    }

    private String firstValue(List<Map<String, Object>> sources, String... names) {
        for (String name : names) {
            for (Map<String, Object> source : sources) {
                String value = text(source.get(name));
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            return null;
        }
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                String text = text(item);
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
            return null;
        }
        return String.valueOf(value).trim();
    }
}
