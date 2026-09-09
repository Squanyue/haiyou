package com.haiyou.shuzhi.exhibition.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateVO;
import com.haiyou.shuzhi.exhibition.service.EadCallbackService;
import com.haiyou.shuzhi.exhibition.service.FeishuBitableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EAD 审批回调业务实现
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EadCallbackServiceImpl implements EadCallbackService {

    /**
     * EAD 回调未带回 record_id 时的默认值
     */
    private static final String DEFAULT_RECORD_ID = "recvqPN5Z2hVGl";

    /**
     * 默认数据表（与 processInstanceStart 常用表一致）
     */
    private static final String DEFAULT_TABLE_ID = "tbloFrfGT96PjmZi";

    /**
     * 飞书标题/结果写入字段（当前表已有列）
     */
    private static final String FEISHU_TITLE_FIELD = "文本测试";
    private static final String FEISHU_PERSON_FIELD = "人员";
    private static final String FEISHU_PERSON_DEPARTMENT_FIELD = "人员.部门";
    private static final String DEFAULT_USER_ID_TYPE = "open_id";
    private static final String EAD_CC_DEPARTMENT_ONE = "1";
    private static final String FEISHU_CHAIRMAN_OFFICE_DEPARTMENT_OPTION = "董事长办公室";

    private final ObjectMapper objectMapper;
    private final FeishuBitableService feishuBitableService;

    @Override
    public FeishuRecordUpdateVO handleApprovedCallback(Map<String, Object> callbackParams) {
        if (callbackParams == null || callbackParams.isEmpty()) {
            throw new IllegalArgumentException("EAD 回调参数不能为空");
        }

        Map<String, String> beanMap = parseBeanMap(callbackParams);
        String recordId = resolveRecordId(callbackParams, beanMap);
        String tableId = resolveTableId(callbackParams, beanMap);
        Map<String, Object> fields = buildUpdateFields(beanMap);

        log.info("EAD 回调成功，准备更新飞书多维表格, tableId={}, recordId={}, fields={}",
                tableId, recordId, fields);

        FeishuRecordUpdateRequest updateRequest = new FeishuRecordUpdateRequest();
        updateRequest.setTableId(tableId);
        updateRequest.setRecordId(recordId);
        updateRequest.setUserIdType(DEFAULT_USER_ID_TYPE);
        updateRequest.setFields(fields);

        FeishuRecordUpdateVO updateVO = feishuBitableService.updateRecord(updateRequest);
        log.info("EAD 回调后飞书记录更新成功, recordId={}", recordId);
        return updateVO;
    }

    private Map<String, String> parseBeanMap(Map<String, Object> callbackParams) {
        Map<String, String> beanMap = new LinkedHashMap<String, String>();
        Object resultStringObj = callbackParams.get("resultString");
        if (resultStringObj == null) {
            return beanMap;
        }

        try {
            JsonNode root;
            if (resultStringObj instanceof String) {
                String resultString = ((String) resultStringObj).trim();
                if (!StringUtils.hasText(resultString)) {
                    return beanMap;
                }
                root = objectMapper.readTree(resultString);
            } else {
                root = objectMapper.valueToTree(resultStringObj);
            }

            JsonNode beanNode = root.get("bean");
            if (beanNode == null || !beanNode.isArray()) {
                return beanMap;
            }
            for (JsonNode item : beanNode) {
                String name = textOrNull(item.get("name"));
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                String value = textOrNull(item.get("value"));
                beanMap.put(name, value);
            }
        } catch (Exception ex) {
            log.warn("解析 resultString.bean 失败: {}", ex.getMessage());
        }
        return beanMap;
    }

    private String resolveRecordId(Map<String, Object> callbackParams, Map<String, String> beanMap) {
        String recordId = firstNonBlank(
                asText(callbackParams.get("record_id")),
                asText(callbackParams.get("recordId")),
                beanMap.get("record_id"),
                beanMap.get("recordId"));
        if (!StringUtils.hasText(recordId)) {
            log.info("EAD 回调未包含 record_id，使用默认值: {}", DEFAULT_RECORD_ID);
            return DEFAULT_RECORD_ID;
        }
        return recordId;
    }

    private String resolveTableId(Map<String, Object> callbackParams, Map<String, String> beanMap) {
        String tableId = firstNonBlank(
                asText(callbackParams.get("table_id")),
                asText(callbackParams.get("tableId")),
                asText(callbackParams.get("tableID")),
                beanMap.get("table_id"),
                beanMap.get("tableId"),
                beanMap.get("tableID"));
        if (!StringUtils.hasText(tableId)) {
            log.info("EAD 回调未包含 tableId，使用默认值: {}", DEFAULT_TABLE_ID);
            return DEFAULT_TABLE_ID;
        }
        return tableId;
    }

    private Map<String, Object> buildUpdateFields(Map<String, String> beanMap) {
        Map<String, Object> fields = new HashMap<String, Object>(6);

        // 当前表已知字段仅有「文本测试」等，避免写入不存在的列名触发 1254045
        String title = firstNonBlank(beanMap.get("title"), beanMap.get("文本测试"));
        if (StringUtils.hasText(title)) {
            fields.put(FEISHU_TITLE_FIELD, title + "【已通过】");
        } else {
            fields.put(FEISHU_TITLE_FIELD, "已通过");
        }

        String ccUser = beanMap.get("ccUser");
        if (StringUtils.hasText(ccUser)) {
            Map<String, String> person = new HashMap<String, String>(2);
            person.put("id", ccUser.trim());
            List<Map<String, String>> persons = Arrays.asList(person);
            fields.put(FEISHU_PERSON_FIELD, persons);
        }

        String ccDepartment = beanMap.get("ccDepartment");
        if (StringUtils.hasText(ccDepartment)) {
            String departmentOption = ccDepartment.trim();
            if (EAD_CC_DEPARTMENT_ONE.equals(departmentOption)) {
                departmentOption = FEISHU_CHAIRMAN_OFFICE_DEPARTMENT_OPTION;
            }
            fields.put(FEISHU_PERSON_DEPARTMENT_FIELD, Arrays.asList(departmentOption));
        }
        return fields;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
