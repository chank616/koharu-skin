package top.twinklestars.koharuskin.admin;

import org.apache.ibatis.annotations.*;
import top.twinklestars.koharuskin.player.Player;
import java.util.List;

@Mapper
public interface AdminPlayersMapper {
    @Select("SELECT pid,uid,name,tid_skin,tid_cape,last_modified FROM players WHERE ${query.where} ORDER BY ${query.order} LIMIT 10 OFFSET #{offset}")
    List<Player> list(@Param("query") AdminSearch query, long offset);
    @Select("SELECT COUNT(*) FROM players WHERE ${query.where}")
    long count(@Param("query") AdminSearch query);
    @Update("UPDATE players SET uid=#{uid},last_modified=CURRENT_TIMESTAMP WHERE pid=#{pid}")
    void owner(long pid, long uid);
}
