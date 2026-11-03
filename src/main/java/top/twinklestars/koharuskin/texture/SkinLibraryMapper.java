package top.twinklestars.koharuskin.texture;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface SkinLibraryMapper {
    record Item(int tid, String name, String type, long uploader, @JsonProperty("public") boolean visible, int likes, String nickname) {}
    String FILTER = """
            FROM textures t LEFT JOIN users u ON t.uploader=u.uid
            WHERE (#{admin}=true OR t.public=true OR t.uploader=#{uid})
            <choose><when test="filter == 'skin'">AND t.type IN ('steve','alex')</when><otherwise>AND t.type=#{filter}</otherwise></choose>
            <if test="!keyword.isEmpty()">AND t.name LIKE CONCAT('%',#{keyword},'%')</if>
            <if test="uploader != null and uploader > 0">AND t.uploader=#{uploader}</if>
            """;
    @Select("<script>SELECT t.tid,t.name,t.type,t.uploader,t.public AS visible,t.likes,COALESCE(u.nickname,'已注销用户') AS nickname " + FILTER
            + " ORDER BY <choose><when test=\"sort == 'likes'\">t.likes</when><otherwise>t.upload_at</otherwise></choose> DESC,t.tid DESC LIMIT 20 OFFSET #{offset}</script>")
    List<Item> list(long uid, boolean admin, String filter, String keyword, Long uploader, String sort, int offset);
    @Select("<script>SELECT COUNT(*) " + FILTER + "</script>")
    int count(long uid, boolean admin, String filter, String keyword, Long uploader);
}
