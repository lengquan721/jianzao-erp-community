package org.erp.master.domain.bo;

import java.util.List;

/** 选中项批量回显；ID 允许按十进制字符串传输。 */
public record MasterOptionIdsBo(List<Long> ids) {
}
