package top.twinklestars.koharuskin.texture;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface TextureMapper {
    @Select("SELECT tid,name,type,hash,size,uploader,public AS visible,upload_at,likes FROM textures WHERE tid=#{tid}")
    Texture find(int tid);
    @Select("SELECT tid FROM textures WHERE hash=#{hash} ORDER BY tid LIMIT 1")
    Integer idByHash(String hash);
    @Select("SELECT tid_skin FROM players WHERE name=#{name} LIMIT 1")
    Integer playerSkin(String name);
    @Select("SELECT tid,name,type,hash,size,uploader,public AS visible,upload_at,likes FROM textures WHERE hash=#{hash} AND (public=true OR uploader=#{uid}) ORDER BY tid LIMIT 1")
    Texture duplicate(String hash, long uid);
    @Select("SELECT tid,name,type,hash,size,uploader,public AS visible,upload_at,likes FROM textures WHERE hash=#{hash} AND uploader=#{uid} AND type=#{type} ORDER BY tid LIMIT 1")
    Texture ownedDuplicate(String hash, long uid, String type);
    @Insert("INSERT INTO textures(name,type,hash,size,uploader,public,upload_at,likes) VALUES(#{name},#{type},#{hash},#{size},#{uploader},#{visible},CURRENT_TIMESTAMP,1)")
    @Options(useGeneratedKeys = true, keyProperty = "tid")
    void insert(Map<String, Object> row);
    @Select("SELECT COUNT(*) FROM textures WHERE hash=#{hash}")
    int countHash(String hash);
    @Update("UPDATE textures SET name=#{name} WHERE tid=#{tid}")
    void rename(int tid, String name);
    @Update("UPDATE textures SET type=#{type} WHERE tid=#{tid}")
    void type(int tid, String type);
    @Update("UPDATE textures SET public=#{visible} WHERE tid=#{tid}")
    void privacy(int tid, boolean visible);
    @Delete("DELETE FROM textures WHERE tid=#{tid}")
    void delete(int tid);
    @Select("SELECT DISTINCT user_uid FROM user_closet WHERE texture_tid=#{tid}")
    List<Long> collectors(int tid);
    @Update("UPDATE users SET avatar=0 WHERE avatar=#{tid}")
    void clearAvatars(int tid);
    @Update("UPDATE users SET avatar=#{tid} WHERE uid=#{uid}")
    void avatar(long uid, int tid);
    @Select("SELECT avatar FROM users WHERE uid=#{uid}")
    Integer avatarId(long uid);
    @Update("""
            UPDATE players SET tid_skin=CASE WHEN tid_skin=#{tid} THEN 0 ELSE tid_skin END,
            tid_cape=CASE WHEN tid_cape=#{tid} THEN 0 ELSE tid_cape END,last_modified=CURRENT_TIMESTAMP
            WHERE (tid_skin=#{tid} OR tid_cape=#{tid}) AND uid != #{exceptUid}
            """)
    void clearPlayers(int tid, long exceptUid);
}
