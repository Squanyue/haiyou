package com.haiyou.shuzhi.exhibition.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 飞书查询记录接口原始响应
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
public class FeishuRecordSearchResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer code;

    private String msg;

    private DataBody data;

    @Data
    public static class DataBody implements Serializable {

        private static final long serialVersionUID = 1L;

        private List<FeishuRecordSearchVO.RecordItem> items;

        @JsonProperty("has_more")
        private Boolean hasMore;

        @JsonProperty("page_token")
        private String pageToken;

        private Integer total;
    }
}
