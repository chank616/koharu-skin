package top.twinklestars.koharuskin.site;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SiteOptions {
    @Select("SELECT option_value FROM options WHERE option_name = #{name} LIMIT 1")
    String get(String name);
}
