package org.erp.common.sequence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 单据编号服务：CG/SC/XS/SK/FK/QT/SR/ZC + yyyyMMdd + 4位流水
 *
 * 独立事务（REQUIRES_NEW）原子递增，避免业务事务回滚吞掉流水。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SequenceService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbcTemplate;

    /**
     * 生成下一个单据号。
     *
     * @param bizType 单据类型前缀（如 CG/XS/CGD）
     * @return 单号：前缀 + yyyyMMdd + 4 位流水
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public String nextNo(String bizType) {
        String tenantId = TenantHelper.getTenantId();
        if (StringUtils.isBlank(tenantId)) {
            throw new ServiceException("单号生成失败（40903）：租户未解析");
        }
        String seqDate = LocalDate.now().format(FMT);
        int rows = jdbcTemplate.update(
            "update sys_sequence set current_value = current_value + 1 " +
            "where biz_type = ? and seq_date = ? and tenant_id = ?", bizType, seqDate, tenantId);
        if (rows == 0) {
            try {
                jdbcTemplate.update(
                    "insert into sys_sequence (tenant_id, biz_type, seq_date, current_value) " +
                    "values (?, ?, ?, 1)", tenantId, bizType, seqDate);
            } catch (Exception e) {
                // 并发插入冲突则重取
                jdbcTemplate.update(
                    "update sys_sequence set current_value = current_value + 1 " +
                    "where biz_type = ? and seq_date = ? and tenant_id = ?", bizType, seqDate, tenantId);
            }
        }
        Long value = jdbcTemplate.queryForObject(
            "select current_value from sys_sequence " +
            "where biz_type = ? and seq_date = ? and tenant_id = ?",
            Long.class, bizType, seqDate, tenantId);
        if (value == null) {
            throw new ServiceException("单号生成失败（40903）：" + bizType);
        }
        return bizType + seqDate + String.format("%04d", value);
    }
}
