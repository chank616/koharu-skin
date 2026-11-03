package top.twinklestars.koharuskin.admin;

import org.apache.ibatis.annotations.*;
import top.twinklestars.koharuskin.player.Player;
import java.util.List;

@Mapper
public interface AdminPlayersMapper {
    String FILTER=" WHERE name LIKE CONCAT('%',#{query},'%') OR CAST(pid AS CHAR)=#{query} OR CAST(uid AS CHAR)=#{query}";
    @Select("SELECT pid,uid,name,tid_skin,tid_cape,last_modified FROM players"+FILTER+" ORDER BY pid DESC LIMIT 10 OFFSET #{offset}")
    List<Player> list(String query, long offset);
    @Select("SELECT COUNT(*) FROM players"+FILTER)
    long count(String query);
    @Update("UPDATE players SET uid=#{uid},last_modified=CURRENT_TIMESTAMP WHERE pid=#{pid}")
    void owner(long pid, long uid);
}
