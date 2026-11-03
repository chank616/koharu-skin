package top.twinklestars.koharuskin.extension;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface SkinApiMapper {
    @Select("""
            SELECT u.uid,u.nickname,p.pid,p.name FROM users u LEFT JOIN players p ON p.uid=u.uid
            WHERE LOWER(u.email) LIKE CONCAT('%',#{suffix}) ORDER BY u.uid,p.pid
            """)
    List<Role> bySuffix(String suffix);
    record Role(long uid,String nickname,Long pid,String name) {}
}
