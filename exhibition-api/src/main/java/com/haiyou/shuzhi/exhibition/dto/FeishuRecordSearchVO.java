package com.haiyou.shuzhi.exhibition.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 多维表格查询记录响应
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
public class FeishuRecordSearchVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<RecordItem> items;

    private Boolean hasMore;

    private String pageToken;

    private Integer total;

    @Data
    public static class RecordItem implements Serializable {

        private static final long serialVersionUID = 1L;

        private Map<String, Object> fields;

        @JsonProperty("record_id")
        private String recordId;

        private String id;

        @JsonProperty("created_by")
        private Person createdBy;

        @JsonProperty("created_time")
        private Long createdTime;

        @JsonProperty("last_modified_by")
        private Person lastModifiedBy;

        @JsonProperty("last_modified_time")
        private Long lastModifiedTime;

        @JsonProperty("shared_url")
        private String sharedUrl;

        @JsonProperty("record_url")
        private String recordUrl;
    }

    @Data
    public static class Person implements Serializable {

        private static final long serialVersionUID = 1L;

        private String id;

        private String name;

        @JsonProperty("en_name")
        private String enName;

        private String email;

        @JsonProperty("avatar_url")
        private String avatarUrl;
    }
}
