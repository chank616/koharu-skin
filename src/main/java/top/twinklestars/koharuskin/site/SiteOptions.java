package top.twinklestars.koharuskin.site;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SiteOptions {
    @Select("SELECT option_value FROM options WHERE option_name = #{name} LIMIT 1")
    String get(String name);

    default String text(String name, String fallback) {
        String value = get(name);
        return value == null ? fallback : value;
    }

    default int number(String name, int fallback) { return Integer.parseInt(text(name, "" + fallback)); }

    default boolean enabled(String name, boolean fallback) { return Boolean.parseBoolean(text(name, "" + fallback)); }

    // 旧表没有唯一索引；分配账号和角色名时共用这行锁，保留历史重复数据。
    @Select("SELECT option_value FROM options WHERE option_name = 'register_with_player_name' FOR UPDATE")
    String lockRegistration();
}
