package com.haiyou.shuzhi.exhibition.service;

import com.haiyou.shuzhi.exhibition.dto.ApprovalHandlingResult;

import java.util.Map;

/**
 * 新版 EAD JSON 审批结果回调服务。
 */
public interface EadApprovalResultService {

    ApprovalHandlingResult handleResult(Map<String, Object> callbackParams);
}
