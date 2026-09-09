package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 多维表格更新记录响应
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
public class FeishuRecordUpdateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 更新后的记录
     */
    private FeishuRecordSearchVO.RecordItem record;
}
