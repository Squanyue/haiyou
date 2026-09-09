package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 前端申请 EAD 令牌请求
 *
 * @author exhibition
 * @date 2026-07-29
 */
@Data
public class EadGetTokenRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 系统用户账号（前端传入）
     */
    @NotBlank(message = "userAccount 不能为空")
    private String userAccount;
}
