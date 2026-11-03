package top.twinklestars.koharuskin.closet;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ClosetMapper {
    String FILTER = """
            FROM user_closet c JOIN textures t ON t.tid=c.texture_tid WHERE c.user_uid=#{uid}
            <choose><when test="category == 'cape'">AND t.type='cape'</when><otherwise>AND t.type IN ('steve','alex')</otherwise></choose>
            <if test="!q.isEmpty()">AND c.item_name LIKE CONCAT('%',#{q},'%')</if>
            """;
    @Select("<script>SELECT t.tid,t.name,t.type,t.hash,t.size,t.uploader,t.public AS visible,t.upload_at,t.likes,c.item_name,c.user_uid "
            + FILTER + " ORDER BY t.tid DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<ClosetItem> list(long uid, String category, String q, int size, int offset);
    @Select("<script>SELECT COUNT(*) " + FILTER + "</script>")
    int count(long uid, String category, String q);
    @Select("SELECT texture_tid FROM user_closet WHERE user_uid=#{uid}")
    List<Integer> ids(long uid);
    @Insert("INSERT INTO user_closet(user_uid,texture_tid,item_name) VALUES(#{uid},#{tid},#{name})")
    void add(long uid, int tid, String name);
    @Update("UPDATE user_closet SET item_name=#{name} WHERE user_uid=#{uid} AND texture_tid=#{tid}")
    int rename(long uid, int tid, String name);
    @Delete("DELETE FROM user_closet WHERE user_uid=#{uid} AND texture_tid=#{tid}")
    int remove(long uid, int tid);
    @Update("UPDATE textures SET likes=GREATEST(0,likes+#{amount}) WHERE tid=#{tid}")
    void likes(int tid, int amount);
}
