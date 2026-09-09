package com.haiyou.shuzhi.exhibition.service.impl;

import com.haiyou.shuzhi.exhibition.config.FeishuProperties;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateResponse;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordSearchRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordSearchResponse;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordSearchVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateResponse;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateVO;
import com.haiyou.shuzhi.exhibition.service.FeishuAuthService;
import com.haiyou.shuzhi.exhibition.service.FeishuBitableService;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书多维表格服务实现
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuBitableServiceImpl implements FeishuBitableService {

    private static final String SEARCH_PATH = "/open-apis/bitable/v1/apps/{app_token}/tables/{table_id}/records/search";
    private static final String CREATE_PATH = "/open-apis/bitable/v1/apps/{app_token}/tables/{table_id}/records";
    private static final String UPDATE_PATH = "/open-apis/bitable/v1/apps/{app_token}/tables/{table_id}/records/{record_id}";

    private final RestTemplate restTemplate;
    private final FeishuProperties feishuProperties;
    private final FeishuAuthService feishuAuthService;

    @Override
    public FeishuRecordSearchVO searchRecords(FeishuRecordSearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }

        String appToken = resolveValue(request.getAppToken(), feishuProperties.getAppToken());
        String tableId = resolveValue(request.getTableId(), feishuProperties.getTableId());
        if (!StringUtils.hasText(appToken) || !StringUtils.hasText(tableId)) {
            throw new IllegalArgumentException("appToken 和 tableId 不能为空，请在请求体中传入或在 application.yml 配置 feishu.app-token / feishu.table-id");
        }

        String accessToken = resolveAccessToken(request.getAccessToken());
        String url = buildSearchUrl(appToken, tableId, request);
        Map<String, Object> body = buildSearchBody(request);

        FeishuRecordSearchResponse response = exchange(
                url, HttpMethod.POST, accessToken, body, FeishuRecordSearchResponse.class, "查询记录");

        if (response.getCode() == null || response.getCode() != 0) {
            throwFeishuError(response.getCode(), response.getMsg());
        }

        FeishuRecordSearchVO vo = new FeishuRecordSearchVO();
        if (response.getData() == null) {
            vo.setItems(Collections.<FeishuRecordSearchVO.RecordItem>emptyList());
            vo.setHasMore(Boolean.FALSE);
            vo.setTotal(0);
            return vo;
        }

        FeishuRecordSearchResponse.DataBody data = response.getData();
        vo.setItems(data.getItems() == null
                ? Collections.<FeishuRecordSearchVO.RecordItem>emptyList()
                : data.getItems());
        vo.setHasMore(data.getHasMore());
        vo.setPageToken(data.getPageToken());
        vo.setTotal(data.getTotal());
        return vo;
    }

    @Override
    public FeishuRecordCreateVO createRecord(FeishuRecordCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.getFields() == null || request.getFields().isEmpty()) {
            throw new IllegalArgumentException("fields 不能为空");
        }

        String appToken = resolveValue(request.getAppToken(), feishuProperties.getAppToken());
        String tableId = resolveValue(request.getTableId(), feishuProperties.getTableId());
        if (!StringUtils.hasText(appToken) || !StringUtils.hasText(tableId)) {
            throw new IllegalArgumentException("appToken 和 tableId 不能为空，请在请求体中传入或在 application.yml 配置 feishu.app-token / feishu.table-id");
        }

        String accessToken = resolveAccessToken(request.getAccessToken());
        String url = buildCreateUrl(appToken, tableId, request);
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("fields", request.getFields());

        log.info("调用飞书新增记录, appToken={}, tableId={}, fields={}", appToken, tableId, request.getFields());
        FeishuRecordCreateResponse response = exchange(
                url, HttpMethod.POST, accessToken, body, FeishuRecordCreateResponse.class, "新增记录");

        if (response.getCode() == null || response.getCode() != 0) {
            throwFeishuError(response.getCode(), response.getMsg());
        }

        FeishuRecordCreateVO vo = new FeishuRecordCreateVO();
        if (response.getData() != null) {
            vo.setRecord(response.getData().getRecord());
        }
        return vo;
    }

    @Override
    public FeishuRecordUpdateVO updateRecord(FeishuRecordUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.getFields() == null || request.getFields().isEmpty()) {
            throw new IllegalArgumentException("fields 不能为空");
        }

        String appToken = resolveValue(request.getAppToken(), feishuProperties.getAppToken());
        String tableId = resolveValue(request.getTableId(), feishuProperties.getTableId());
        String recordId = request.getRecordId();
        if (!StringUtils.hasText(appToken) || !StringUtils.hasText(tableId) || !StringUtils.hasText(recordId)) {
            throw new IllegalArgumentException("appToken、tableId、recordId 不能为空");
        }

        String accessToken = resolveAccessToken(request.getAccessToken());
        String url = buildUpdateUrl(appToken, tableId, recordId, request);
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("fields", request.getFields());

        log.info("调用飞书更新记录, appToken={}, tableId={}, recordId={}", appToken, tableId, recordId);
        FeishuRecordUpdateResponse response = exchange(
                url, HttpMethod.PUT, accessToken, body, FeishuRecordUpdateResponse.class, "更新记录");

        if (response.getCode() == null || response.getCode() != 0) {
            throwFeishuError(response.getCode(), response.getMsg());
        }

        FeishuRecordUpdateVO vo = new FeishuRecordUpdateVO();
        if (response.getData() != null) {
            vo.setRecord(response.getData().getRecord());
        }
        return vo;
    }

    private <T> T exchange(String url, HttpMethod method, String accessToken, Object body,
                           Class<T> responseType, String actionName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        HttpEntity<Object> entity = new HttpEntity<Object>(body, headers);

        try {
            ResponseEntity<T> responseEntity = restTemplate.exchange(url, method, entity, responseType);
            T response = responseEntity.getBody();
            if (response == null) {
                throw new IllegalStateException("飞书" + actionName + "返回为空");
            }
            return response;
        } catch (RestClientException ex) {
            log.error("调用飞书{}失败", actionName, ex);
            throw new IllegalStateException("调用飞书" + actionName + "失败: " + ex.getMessage(), ex);
        }
    }

    private void throwFeishuError(Integer code, String msg) {
        String message = StringUtils.hasText(msg) ? msg : "未知错误";
        log.warn("飞书接口失败, code={}, msg={}", code, message);
        throw new IllegalStateException("飞书返回错误, code=" + code + ", msg=" + message);
    }

    private String buildSearchUrl(String appToken, String tableId, FeishuRecordSearchRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(feishuProperties.getBaseUrl() + SEARCH_PATH
                        .replace("{app_token}", appToken)
                        .replace("{table_id}", tableId));

        if (StringUtils.hasText(request.getUserIdType())) {
            builder.queryParam("user_id_type", request.getUserIdType());
        }
        if (StringUtils.hasText(request.getPageToken())) {
            builder.queryParam("page_token", request.getPageToken());
        }
        if (request.getPageSize() != null) {
            builder.queryParam("page_size", request.getPageSize());
        }
        return builder.toUriString();
    }

    private String buildCreateUrl(String appToken, String tableId, FeishuRecordCreateRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(feishuProperties.getBaseUrl() + CREATE_PATH
                        .replace("{app_token}", appToken)
                        .replace("{table_id}", tableId));

        if (StringUtils.hasText(request.getUserIdType())) {
            builder.queryParam("user_id_type", request.getUserIdType());
        }
        if (StringUtils.hasText(request.getClientToken())) {
            builder.queryParam("client_token", request.getClientToken());
        }
        if (request.getIgnoreConsistencyCheck() != null) {
            builder.queryParam("ignore_consistency_check", request.getIgnoreConsistencyCheck());
        }
        return builder.toUriString();
    }

    private String buildUpdateUrl(String appToken, String tableId, String recordId,
                                  FeishuRecordUpdateRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(feishuProperties.getBaseUrl() + UPDATE_PATH
                        .replace("{app_token}", appToken)
                        .replace("{table_id}", tableId)
                        .replace("{record_id}", recordId));

        if (StringUtils.hasText(request.getUserIdType())) {
            builder.queryParam("user_id_type", request.getUserIdType());
        }
        if (request.getIgnoreConsistencyCheck() != null) {
            builder.queryParam("ignore_consistency_check", request.getIgnoreConsistencyCheck());
        }
        return builder.toUriString();
    }

    private Map<String, Object> buildSearchBody(FeishuRecordSearchRequest request) {
        Map<String, Object> body = new HashMap<String, Object>(8);
        if (StringUtils.hasText(request.getViewId())) {
            body.put("view_id", request.getViewId());
        }
        if (request.getFieldNames() != null) {
            body.put("field_names", request.getFieldNames());
        }
        if (request.getSort() != null && !request.getSort().isEmpty()) {
            List<Map<String, Object>> sortList = new ArrayList<Map<String, Object>>();
            for (FeishuRecordSearchRequest.SortItem item : request.getSort()) {
                Map<String, Object> sortItem = new HashMap<String, Object>(4);
                sortItem.put("field_name", item.getFieldName());
                sortItem.put("desc", item.getDesc() != null && item.getDesc());
                sortList.add(sortItem);
            }
            body.put("sort", sortList);
        }
        if (request.getFilter() != null) {
            body.put("filter", buildFilterBody(request.getFilter()));
        }
        if (request.getAutomaticFields() != null) {
            body.put("automatic_fields", request.getAutomaticFields());
        }
        return body;
    }

    private Map<String, Object> buildFilterBody(FeishuRecordSearchRequest.FilterInfo filter) {
        Map<String, Object> filterBody = new HashMap<String, Object>(4);
        if (StringUtils.hasText(filter.getConjunction())) {
            filterBody.put("conjunction", filter.getConjunction());
        }
        if (filter.getConditions() != null) {
            List<Map<String, Object>> conditions = new ArrayList<Map<String, Object>>();
            for (FeishuRecordSearchRequest.Condition condition : filter.getConditions()) {
                Map<String, Object> conditionBody = new HashMap<String, Object>(4);
                conditionBody.put("field_name", condition.getFieldName());
                conditionBody.put("operator", condition.getOperator());
                conditionBody.put("value", condition.getValue());
                conditions.add(conditionBody);
            }
            filterBody.put("conditions", conditions);
        }
        return filterBody;
    }

    private String resolveValue(String requestValue, String configValue) {
        if (StringUtils.hasText(requestValue)) {
            return requestValue;
        }
        return configValue;
    }

    private String resolveAccessToken(String accessToken) {
        if (StringUtils.hasText(accessToken)) {
            return accessToken;
        }
        return feishuAuthService.getTenantAccessToken();
    }
}
