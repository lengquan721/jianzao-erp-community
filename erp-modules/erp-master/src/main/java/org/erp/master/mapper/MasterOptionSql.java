package org.erp.master.mapper;

/** 仅用于主数据 options 的静态 SQL 片段。所有动态值走绑定参数，m 是当前主数据行。 */
final class MasterOptionSql {
    private MasterOptionSql() {
    }

    static final String FILTER = """
        where m.del_flag = '0'
        <if test="q.status != null">and m.status = #{q.status}</if>
        <if test="q.code != null">and m.code = #{q.code}</if>
        """;
    static final String SEARCH_START = """
        <if test="q.keyword != null">
            and (m.code like #{q.prefix} escape '!'
                <if test="q.contains != null">
                    or m.name like #{q.contains} escape '!'
        """;
    static final String SEARCH_END = """
                </if>
            )
        </if>
        """;
    static final String NAME_SEARCH = SEARCH_START + SEARCH_END;
    static final String PARTY_SEARCH = SEARCH_START + "or m.phone like #{q.contains} escape '!'\n" + SEARCH_END;
    static final String PRODUCT_SEARCH = SEARCH_START + "or m.spec like #{q.contains} escape '!'\n" + SEARCH_END;

    static final String ORDER_START = """
        order by
        <if test="q.keyword != null">
            case when m.code = #{q.keyword} then 0 when m.code like #{q.prefix} escape '!' then 1 else 2 end,
        </if>
        """;
    static final String LIMIT = " limit #{q.fetchSize} offset #{q.offset}</script>";
    static final String IDS = """
        where m.del_flag = '0' and m.id in
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """;
}
