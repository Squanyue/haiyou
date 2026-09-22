package com.haiyou.shuzhi.exhibition.common;

/**
 * 飞书表格中不会随部署环境变化的固定字段名。
 *
 * <p>表 ID、Token、地址、流程编码以及本地文件路径等环境参数仍由 YAML 提供。</p>
 */
public final class FeishuConstants {

    private FeishuConstants() {
    }

    // 上架申请和应用索引的业务关联键
    public static final String UNIQUE_IDENTIFIER_FIELD = "唯一标识";

    // 上架申请/使用申请表字段和状态
    public static final String STATUS_FIELD = "状态";
    public static final String SOURCE_FIELD = "审批来源";
    public static final String APPROVAL_INSTANCE_FIELD = "审批实例ID";
    public static final String CURRENT_NODE_FIELD = "当前审批节点";
    public static final String REJECTION_REASON_FIELD = "退回原因";
    public static final String APPLICATION_NO_FIELD = "申请单号";
    public static final String APPLICATION_ID_FIELD = "关联应用ID";
    public static final String APPLICATION_TYPE_ID_FIELD = "应用类型ID";
    public static final String APPLICANT_ID_FIELD = "申请人ID";
    public static final String AUTHORIZED_DEPARTMENT_FIELD = "授权部门";
    public static final String AUTHORIZED_USER_FIELD = "授权用户";
    /** 上架/使用申请上记录本笔已发放积分，用于回调幂等续写。 */
    public static final String POINTS_AWARD_RECORD_FIELD = "积分发放记录";
    public static final String USE_APPLICATION_ID_FIELD = "应用ID";
    /** 使用申请表业务编号字段；当前表结构用「主键」（如 UA004），没有「申请编号」列。 */
    public static final String USE_APPLICATION_NO_FIELD = "主键";
    public static final String USE_REASON_FIELD = "申请理由";
    public static final String DICTIONARY_DEPARTMENT_FIELD = "所属部门ID";
    public static final String DICTIONARY_USER_ID_FIELD = "用户ID";

    /** 申请表「状态」选项：与飞书单选文案保持一致。 */
    public static final String PENDING_STATUS = "审批中";
    public static final String APPROVED_STATUS = "已通过";
    public static final String REJECTED_STATUS = "已退回";
    public static final String CANCELED_STATUS = "已撤回";

    /** 申请表「审批来源」选项：与飞书单选文案保持一致。 */
    public static final String FEISHU_SOURCE = "飞书审批";
    public static final String EAD_SOURCE = "EAD审批";

    // 应用索引表字段和状态
    public static final String APPLICATION_INDEX_APPLICATION_ID_FIELD = "应用ID";
    public static final String APPLICATION_INDEX_STATUS_FIELD = "状态";
    public static final String APPLICATION_INDEX_PUBLISH_TIME_FIELD = "发布时间";
    public static final String APPLICATION_INDEX_OWNER_FIELD = "负责人ID";
    public static final String APPLICATION_INDEX_DEVELOPER_FIELD = "开发者ID";
    /** 应用索引「状态」选项：上架审批通过后写入。 */
    public static final String APPLICATION_PUBLISHED_STATUS = "已上架";

    // 积分余额表字段
    public static final String POINTS_BALANCE_KEY_FIELD = "主键";
    public static final String POINTS_BALANCE_USER_ID_FIELD = "用户ID";
    public static final String POINTS_BALANCE_TOTAL_FIELD = "当前总积分";
    public static final String POINTS_BALANCE_APPLICATION_BUILD_FIELD = "应用建设累计";
    public static final String POINTS_BALANCE_APPLICATION_USE_FIELD = "应用使用累计";
    public static final String POINTS_BALANCE_MONTH_FIELD = "本月积分";
    public static final String POINTS_BALANCE_LAST_MONTH_FIELD = "上月积分";
    public static final String POINTS_BALANCE_UPDATED_AT_FIELD = "最后更新时间";

    // 用户权限表字段
    public static final String PERMISSION_KEY_FIELD = "主键";
    public static final String PERMISSION_APPLICATION_ID_FIELD = "应用ID";
    /** 用户权限表实际字段名：AD账号。 */
    public static final String PERMISSION_USER_ID_FIELD = "AD账号";
    public static final String PERMISSION_TIME_FIELD = "授权时间";
    public static final String PERMISSION_STATUS_FIELD = "状态";
    /** 用户权限「状态」选项。 */
    public static final String PERMISSION_GRANTED_STATUS = "已授权";

    // 通知表字段和值
    public static final String NOTIFICATION_KEY_FIELD = "消息ID";
    public static final String NOTIFICATION_DEDUP_FIELD = "防重键";
    public static final String NOTIFICATION_RECEIVER_FIELD = "接收人ID";
    public static final String NOTIFICATION_TITLE_FIELD = "消息标题";
    public static final String NOTIFICATION_CONTENT_FIELD = "消息内容";
    public static final String NOTIFICATION_CATEGORY_FIELD = "分类";
    public static final String NOTIFICATION_UNREAD_FIELD = "已读状态";
    public static final String NOTIFICATION_TIME_FIELD = "消息时间";
    /** 通知表「分类」「已读状态」选项。 */
    public static final String NOTIFICATION_CATEGORY_VALUE = "审批通知";
    public static final String NOTIFICATION_UNREAD_VALUE = "未读";

    // 上架申请写入的基础表字段
    public static final String TITLE_FIELD = "文本测试";
    public static final String IMAGE_FIELD = "图片测试";
    public static final String VIDEO_FIELD = "视频测试";
    public static final String PERSON_FIELD = "人员";
    public static final String SUPERVISOR_FIELD = "人员.直属上级";
    public static final String EMPLOYEE_NO_FIELD = "人员.工号";

    // RPA 详情和附件资料表字段
    public static final String PRIMARY_KEY_FIELD = "主键";
    public static final String APPLICATION_CODE_FIELD = "应用编码";
    public static final String VERSION_FIELD = "版本号";
    public static final String REMARKS_FIELD = "备注说明";
    public static final String RPA_PLATFORM_FIELD = "RPA所属平台";
    public static final String PROCESS_STEPS_FIELD = "操作流程步骤";
    public static final String APPLICATION_DESCRIPTION_FIELD = "应用描述";
    public static final String ATTACHMENT_FIELD = "附件";
    public static final String UPLOAD_TIME_FIELD = "上传时间";
    public static final String UPLOADER_ID_FIELD = "上传人ID";

    // 上架申请字段到 EAD 表单的固定映射
    public static final String APPLICANT_ACCOUNT_FIELD = "申请人AD账号";
    public static final String DEPARTMENT_ID_FIELD = "所属部门ID";
    public static final String PHONE_FIELD = "联系电话";
    public static final String EMAIL_FIELD = "联系邮箱";
    public static final String APPLICATION_NAME_FIELD = "应用名称";
    public static final String APPLICATION_TYPE_FIELD = "应用类型";
    public static final String BUSINESS_DOMAIN_ID_FIELD = "所属业务域ID";
    public static final String SUMMARY_FIELD = "摘要";
    public static final String APPLICATION_INTRODUCTION_FIELD = "应用简介";
    public static final String COLLABORATION_FIELD = "开发合作方信息";
    public static final String WEB_ADDRESS_FIELD = "应用URL地址";
    public static final String MOBILE_ADDRESS_FIELD = "移动端地址";
    public static final String CONTACT_ACCOUNT_FIELD = "接入人AD账号";
    public static final String APPLICABLE_DEPARTMENT_ID_FIELD = "适用部门ID";
    public static final String APPLICABLE_USER_ACCOUNT_FIELD = "适用用户AD账号";
    public static final String APPLICABLE_ROLE_FIELD = "适用角色";
    public static final String PERMISSION_SCOPE_FIELD = "权限范围";

}
