package top.twinklestars.koharuskin.site;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SiteOptions {
    @org.apache.ibatis.annotations.Insert("INSERT INTO options(option_name,option_value) VALUES(#{name},#{value})")
    void insert(String name, String value);
    @org.apache.ibatis.annotations.Update("UPDATE options SET option_value=#{value} WHERE option_name=#{name}")
    void update(String name, String value);
    @Select("SELECT option_value FROM options WHERE option_name = #{name} LIMIT 1")
    String get(String name);

    default String text(String name, String fallback) {
        String value = get(name);
        return value == null ? fallback : value;
    }

    default String localized(String name,String fallback) { return text(name+"_zh_CN",text(name,fallback)); }

    default int number(String name, int fallback) {
        String value = text(name, "" + fallback);
        return switch (value) { case "true" -> 1; case "false" -> 0; default -> Integer.parseInt(value); };
    }

    default boolean enabled(String name, boolean fallback) { return Boolean.parseBoolean(text(name, "" + fallback)); }

    // 旧表没有唯一索引；分配账号和角色名时共用这行锁，保留历史重复数据。
    @Select("SELECT option_value FROM options WHERE option_name = 'register_with_player_name' FOR UPDATE")
    String lockRegistration();
}
