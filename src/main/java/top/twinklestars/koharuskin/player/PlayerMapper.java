package top.twinklestars.koharuskin.player;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface PlayerMapper {
    @Update("UPDATE users SET permission=-1,remember_token=NULL WHERE uid=#{uid}")
    void ban(long uid);
    @Select("SELECT pid,uid,name,tid_skin,tid_cape,last_modified FROM players WHERE uid=#{uid} ORDER BY pid")
    List<Player> list(long uid);
    @Select("SELECT pid,uid,name,tid_skin,tid_cape,last_modified FROM players WHERE pid=#{pid}")
    Player find(long pid);
    @Select("SELECT pid,uid,name,tid_skin,tid_cape,last_modified FROM players WHERE name=#{name} LIMIT 1")
    Player findByName(String name);
    @Select("SELECT COUNT(*) FROM players WHERE name=#{name}")
    int countName(String name);
    @Select("SELECT score FROM users WHERE uid=#{uid} FOR UPDATE")
    int lockScore(long uid);
    @Select("SELECT score FROM users WHERE uid=#{uid}")
    int score(long uid);
    @Select("SELECT verified FROM users WHERE uid=#{uid}")
    boolean verified(long uid);
    @Update("UPDATE users SET score=score+#{amount} WHERE uid=#{uid}")
    void changeScore(long uid, int amount);
    @Update("UPDATE users SET nickname=#{name} WHERE uid=#{uid}")
    void nickname(long uid, String name);
    @Insert("INSERT INTO players(uid,name,tid_skin,tid_cape,last_modified) VALUES(#{uid},#{name},0,0,CURRENT_TIMESTAMP)")
    void create(long uid, String name);
    @Update("UPDATE players SET name=#{name},last_modified=CURRENT_TIMESTAMP WHERE pid=#{pid}")
    void rename(long pid, String name);
    @Delete("DELETE FROM players WHERE pid=#{pid}")
    void delete(long pid);
    @Delete("DELETE FROM players WHERE uid=#{uid} AND pid<>#{pid}")
    void deleteOtherPlayers(long uid, long pid);
    @Select("SELECT uuid FROM uuid WHERE name=#{name} LIMIT 1")
    String uuid(String name);
    @Insert("INSERT INTO uuid(name,uuid) VALUES(#{name},#{uuid})")
    void insertUuid(String name, String uuid);
    @Delete("DELETE FROM uuid WHERE name=#{name}")
    void deleteUuid(String name);
    @Update("UPDATE uuid SET name=#{newName} WHERE name=#{oldName}")
    void renameUuid(String oldName, String newName);
    @Select("SELECT COUNT(*) FROM user_closet WHERE user_uid=#{uid} AND texture_tid=#{tid}")
    int inCloset(long uid, int tid);
    @Select("SELECT type FROM textures WHERE tid=#{tid}")
    String textureType(int tid);
    @Update("UPDATE players SET tid_skin=#{skin},tid_cape=#{cape},last_modified=CURRENT_TIMESTAMP WHERE pid=#{pid}")
    void textures(long pid, int skin, int cape);
}
