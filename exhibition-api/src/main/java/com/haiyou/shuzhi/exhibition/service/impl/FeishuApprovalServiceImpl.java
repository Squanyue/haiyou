package com.haiyou.shuzhi.exhibition.service.impl;

import com.haiyou.shuzhi.exhibition.config.FeishuProperties;
import com.haiyou.shuzhi.exhibition.dto.ApprovalStatusSnapshot;
import com.haiyou.shuzhi.exhibition.service.FeishuApprovalService;
import com.haiyou.shuzhi.exhibition.service.FeishuAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书审批实例查询实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuApprovalServiceImpl implements FeishuApprovalService {

    private static final String INSTANCE_GET_PATH = "/open-apis/approval/v4/instances/get";

    private final RestTemplate restTemplate;
    private final FeishuProperties feishuProperties;
    private final FeishuAuthService feishuAuthService;

    @Override
    @SuppressWarnings("unchecked")
    public ApprovalStatusSnapshot queryInstance(String approvalInstanceId) {
        if (!StringUtils.hasText(approvalInstanceId)) {
            throw new IllegalArgumentException("审批实例ID不能为空");
        }

        // 本地联调：TEST- 前缀实例不调用飞书，直接视为已通过。
        String trimmedId = approvalInstanceId.trim();
        if (trimmedId.startsWith("TEST-")) {
            log.warn("飞书审批实例走本地联调短路, instanceId={}", trimmedId);
            ApprovalStatusSnapshot snapshot = new ApprovalStatusSnapshot();
            snapshot.setApprovalInstanceId(trimmedId);
            snapshot.setStatus("APPROVED");
            snapshot.setCurrentNode("已通过");
            snapshot.setApprovalTime(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            return snapshot;
        }

        String accessToken = feishuAuthService.getTenantAccessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, String> body = new HashMap<String, String>(1);
        body.put("instance_id", trimmedId);

        Map<String, Object> response;
        try {
            String url = feishuProperties.getBaseUrl() + INSTANCE_GET_PATH;
            ResponseEntity<Map> entity = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<Map<String, String>>(body, headers), Map.class);
            response = entity.getBody();
        } catch (RestClientException ex) {
            throw new IllegalStateException("调用飞书审批实例查询失败: " + ex.getMessage(), ex);
        }

        if (response == null) {
            throw new IllegalStateException("飞书审批实例查询返回为空");
        }
        Integer code = asInteger(response.get("code"));
        if (code == null || code != 0) {
            throw new IllegalStateException("飞书审批实例查询失败, code=" + code + ", msg=" + text(response.get("msg")));
        }

        Object dataValue = response.get("data");
        if (!(dataValue instanceof Map)) {
            throw new IllegalStateException("飞书审批实例查询未返回 data");
        }
        Map<String, Object> data = (Map<String, Object>) dataValue;
        String status = firstText(data, "status", "approval_status", "instance_status");
        if (!StringUtils.hasText(status)) {
            throw new IllegalStateException("飞书审批实例查询未返回审批状态");
        }

        ApprovalStatusSnapshot snapshot = new ApprovalStatusSnapshot();
        snapshot.setApprovalInstanceId(firstNonBlank(
                firstText(data, "instance_id", "instance_code", "approval_instance_id"), trimmedId));
        snapshot.setStatus(status);
        snapshot.setCurrentNode(firstText(data, "current_node", "current_node_name", "node_name"));
        snapshot.setRejectionReason(firstText(data, "comment", "reason", "rejection_reason"));
        snapshot.setApprovalTime(firstText(data, "completed_at", "approval_time", "end_time"));
        log.info("飞书审批实例查询完成, instanceId={}, status={}", snapshot.getApprovalInstanceId(), snapshot.getStatus());
        return snapshot;
    }

    private String firstText(Map<String, Object> values, String... names) {
        for (String name : names) {
            String value = text(values.get(name));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
