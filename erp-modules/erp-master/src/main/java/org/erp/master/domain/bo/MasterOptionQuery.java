package org.erp.master.domain.bo;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;

/** 与管理列表/Excel 分离的选项查询参数。只向 SQL 传绑定参数，通配符按字面匹配。 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class MasterOptionQuery {

    public static final String DEFAULT_SIZE = "30";
    private static final int MAX_SIZE = 100;

    private final String keyword;
    private final String code;
    private final String prefix;
    private final String contains;
    private final Integer status;
    private final int pageNum;
    private final int pageSize;

    public static MasterOptionQuery of(String keyword, String code, Integer status, int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > MAX_SIZE) {
            throw new ServiceException("pageNum 必须大于 0，pageSize 必须在 1–100 之间", 400);
        }
        keyword = normalize(keyword, 100, "keyword");
        code = normalize(code, 32, "code");
        if (keyword != null && code != null) {
            throw new ServiceException("code 和 keyword 不能同时使用", 400);
        }
        if (status != null && status != 0 && status != 1) {
            throw new ServiceException("status 只能为 0 或 1", 400);
        }
        String literal = keyword == null ? null : keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return new MasterOptionQuery(keyword, code, literal == null ? null : literal + "%",
            keyword != null && keyword.codePointCount(0, keyword.length()) >= 2 ? "%" + literal + "%" : null,
            status, pageNum, pageSize);
    }

    private static String normalize(String value, int maxLength, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
            throw new ServiceException(name + " 最多 " + maxLength + " 个字符", 400);
        }
        return trimmed;
    }

    public long getOffset() {
        return (pageNum - 1L) * pageSize;
    }

    public int getFetchSize() {
        return pageSize + 1;
    }
}
