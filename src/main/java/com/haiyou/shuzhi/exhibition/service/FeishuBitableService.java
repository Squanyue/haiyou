package com.haiyou.shuzhi.exhibition.service;

import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordSearchRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordSearchVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateVO;

/**
 * 飞书多维表格服务
 *
 * @author exhibition
 * @date 2026-07-23
 */
public interface FeishuBitableService {

    /**
     * 查询多维表格记录
     *
     * @param request 查询条件
     * @return 查询结果
     */
    FeishuRecordSearchVO searchRecords(FeishuRecordSearchRequest request);

    /**
     * 新增多维表格记录
     *
     * @param request 新增请求
     * @return 新增后的记录
     */
    FeishuRecordCreateVO createRecord(FeishuRecordCreateRequest request);

    /**
     * 更新多维表格记录
     *
     * @param request 更新请求
     * @return 更新后的记录
     */
    FeishuRecordUpdateVO updateRecord(FeishuRecordUpdateRequest request);
}
