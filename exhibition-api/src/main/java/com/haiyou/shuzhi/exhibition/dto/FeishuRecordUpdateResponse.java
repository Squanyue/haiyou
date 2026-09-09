package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 飞书更新记录接口原始响应
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
public class FeishuRecordUpdateResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer code;

    private String msg;

    private DataBody data;

    @Data
    public static class DataBody implements Serializable {

        private static final long serialVersionUID = 1L;

        private FeishuRecordSearchVO.RecordItem record;
    }
}
