package top.twinklestars.koharuskin.site;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface TranslationMapper {
    record Line(long id,String group,String key,String text) {}
    @Select("SELECT id,`group`,`key`,text FROM language_lines ORDER BY id LIMIT 10 OFFSET #{offset}")
    List<Line> page(long offset);
    @Select("SELECT COUNT(*) FROM language_lines") long count();
    @Select("SELECT id,`group`,`key`,text FROM language_lines WHERE `group`=#{group} ORDER BY id")
    List<Line> group(String group);
    @Select("SELECT id,`group`,`key`,text FROM language_lines WHERE id=#{id} FOR UPDATE") Line lock(long id);
    @Insert("INSERT INTO language_lines(`group`,`key`,text,created_at,updated_at) VALUES(#{group},#{key},#{text},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
    void insert(String group,String key,String text);
    @Update("UPDATE language_lines SET text=#{text},updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    void update(long id,String text);
    @Delete("DELETE FROM language_lines WHERE id=#{id}") int delete(long id);
}
