package org.dromara.common.mybatis.core.page;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.core.utils.sql.SqlUtil;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 分页查询实体类
 *
 * @author Lion Li
 */
@Data
public class PageQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分页大小
     */
    private Integer pageSize;

    /**
     * 当前页数
     */
    private Integer pageNum;

    /**
     * 排序列
     */
    private String orderByColumn;

    /**
     * 排序的方向desc或者asc
     */
    private String isAsc;

    /**
     * 当前记录起始索引 默认值
     */
    public static final int DEFAULT_PAGE_NUM = 1;

    /** Default page size for list endpoints. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard upper bound for interactive pagination; export APIs have their own limits. */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * 构建分页对象
     */
    public <T> Page<T> build() {
        return buildWithMaxPageSize(MAX_PAGE_SIZE);
    }

    protected <T> Page<T> buildWithMaxPageSize(int maxPageSize) {
        Integer pageNum = ObjectUtil.defaultIfNull(getPageNum(), DEFAULT_PAGE_NUM);
        Integer pageSize = ObjectUtil.defaultIfNull(getPageSize(), DEFAULT_PAGE_SIZE);
        if (pageNum <= 0) {
            pageNum = DEFAULT_PAGE_NUM;
        }
        if (pageSize <= 0 || pageSize > maxPageSize) {
            throw new ServiceException("分页参数有误：每页数量必须在1到" + maxPageSize + "之间");
        }
        Page<T> page = new Page<>(pageNum, pageSize);
        List<OrderItem> orderItems = buildOrderItem();
        if (CollUtil.isNotEmpty(orderItems)) {
            page.addOrder(orderItems);
        }
        return page;
    }

    /** Fetch a separately bounded export through ordinary, maxLimit-compatible pages. */
    public static <T> List<T> collectForExport(int exportLimit, Function<PageQuery, List<T>> fetchPage) {
        if (exportLimit <= 0 || exportLimit > 5000) {
            throw new ServiceException("导出数量必须在1到5000之间");
        }
        List<T> rows = new ArrayList<>(Math.min(exportLimit, MAX_PAGE_SIZE));
        int pageNum = 1;
        while (rows.size() < exportLimit) {
            // Keep the page size fixed so a partial last page cannot move its SQL offset backwards.
            List<T> pageRows = fetchPage.apply(new PageQuery(MAX_PAGE_SIZE, pageNum++));
            if (pageRows == null || pageRows.isEmpty()) {
                break;
            }
            int remaining = exportLimit - rows.size();
            rows.addAll(pageRows.subList(0, Math.min(remaining, pageRows.size())));
            if (pageRows.size() < MAX_PAGE_SIZE) {
                break;
            }
        }
        return rows;
    }

    /**
     * 构建排序
     *
     * 支持的用法如下:
     * {isAsc:"asc",orderByColumn:"id"} order by id asc
     * {isAsc:"asc",orderByColumn:"id,createTime"} order by id asc,create_time asc
     * {isAsc:"desc",orderByColumn:"id,createTime"} order by id desc,create_time desc
     * {isAsc:"asc,desc",orderByColumn:"id,createTime"} order by id asc,create_time desc
     */
    private List<OrderItem> buildOrderItem() {
        if (StringUtils.isBlank(orderByColumn) || StringUtils.isBlank(isAsc)) {
            return null;
        }
        String orderBy = SqlUtil.escapeOrderBySql(orderByColumn);
        orderBy = StringUtils.toUnderScoreCase(orderBy);

        // 兼容前端排序类型
        isAsc = StringUtils.replaceEach(isAsc, new String[]{"ascending", "descending"}, new String[]{"asc", "desc"});

        String[] orderByArr = orderBy.split(StringUtils.SEPARATOR);
        String[] isAscArr = isAsc.split(StringUtils.SEPARATOR);
        if (isAscArr.length != 1 && isAscArr.length != orderByArr.length) {
            throw new ServiceException("排序参数有误");
        }

        List<OrderItem> list = new ArrayList<>();
        // 每个字段各自排序
        for (int i = 0; i < orderByArr.length; i++) {
            String orderByStr = orderByArr[i];
            String isAscStr = isAscArr.length == 1 ? isAscArr[0] : isAscArr[i];
            if ("asc".equals(isAscStr)) {
                list.add(OrderItem.asc(orderByStr));
            } else if ("desc".equals(isAscStr)) {
                list.add(OrderItem.desc(orderByStr));
            } else {
                throw new ServiceException("排序参数有误");
            }
        }
        return list;
    }

    @JsonIgnore
    public long getFirstNum() {
        long safePageNum = ObjectUtil.defaultIfNull(pageNum, DEFAULT_PAGE_NUM);
        long safePageSize = ObjectUtil.defaultIfNull(pageSize, DEFAULT_PAGE_SIZE);
        return Math.max(0L, safePageNum - 1L) * safePageSize;
    }

    public PageQuery(Integer pageSize, Integer pageNum) {
        this.pageSize = pageSize;
        this.pageNum = pageNum;
    }

}
