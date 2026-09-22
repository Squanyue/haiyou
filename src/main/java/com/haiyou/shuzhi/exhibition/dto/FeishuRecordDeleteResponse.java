package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 飞书删除记录接口原始响应
 */
@Data
public class FeishuRecordDeleteResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer code;

    private String msg;

    private Boolean data;
}
