package com.haiyou.shuzhi.exhibition.service;

import com.haiyou.shuzhi.exhibition.dto.EadTokenVO;

/**
 * EAD 认证服务
 *
 * @author exhibition
 * @date 2026-07-29
 */
public interface EadAuthService {

    /**
     * 向 EAD 申请第三方系统令牌
     *
     * @param userAccount 前端传入的系统用户账号
     * @return 令牌信息
     */
    EadTokenVO getToken(String userAccount);
}
