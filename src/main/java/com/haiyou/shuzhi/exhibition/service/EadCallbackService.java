package com.haiyou.shuzhi.exhibition.service;

import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateVO;

import java.util.Map;

/**
 * EAD 审批回调业务接口
 *
 * @author exhibition
 * @date 2026-07-23
 */
public interface EadCallbackService {

    /**
     * 处理 EAD 审批通过回调：解析入参后更新飞书多维表格
     *
     * @param callbackParams 回调全部参数（含 resultString）
     * @return 飞书更新结果
     */
    FeishuRecordUpdateVO handleApprovedCallback(Map<String, Object> callbackParams);
}
