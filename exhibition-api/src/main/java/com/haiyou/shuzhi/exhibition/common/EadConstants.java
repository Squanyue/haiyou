package com.haiyou.shuzhi.exhibition.common;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * EAD 接口中不会随部署环境变化的固定字段名和参数值。
 *
 * <p>EAD 地址、账号、密钥、流程编码等环境参数仍由 YAML 提供。</p>
 */
public final class EadConstants {

    private EadConstants() {
    }

    /** EAD 流程表单中的业务唯一标识字段。 */
    public static final String BIZ_UNIQUE_KEY = "bizUniqueKey";

    /** EAD 发起流程时的 multipart JSON 字段名。 */
    public static final String PROCESS_START_JSON_FIELD = "createFlowInstance";

    /** EAD 流程附件字段。 */
    public static final String FILES_ICON_FIELD = "filesIcon";
    public static final String FILES_MATERIALS_FIELD = "filesMaterials";
    public static final String FILES_ATTACHMENT_FIELD = "filesAttachment";

    /** EAD 表单固定输入字段顺序。 */
    public static final List<String> INPUT_NAMES = Collections.unmodifiableList(Arrays.asList(
            "applicant", "department", "phone", "email", "name", "type", "domain", "summary", "scenario",
            "collaboration", "webAddress", "mobileAddress", "contactDepartment", "contact", "contactPhone",
            "contactEmail", "accessDepartment", "users", "roles", "radio38", "remarks", BIZ_UNIQUE_KEY
    ));
}
