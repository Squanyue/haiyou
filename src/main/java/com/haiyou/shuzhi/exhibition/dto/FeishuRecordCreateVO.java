package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 多维表格新增记录响应
 *
 * @author exhibition
 * @date 2026-07-30
 */
@Data
public class FeishuRecordCreateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 新创建的记录
     */
    private FeishuRecordSearchVO.RecordItem record;
}
