package top.twinklestars.koharuskin.admin;

import org.apache.ibatis.annotations.*;
import top.twinklestars.koharuskin.account.ProfileMapper.Profile;
import java.util.List;

@Mapper
public interface AdminUsersMapper {
    String FILTER = " WHERE email LIKE CONCAT('%',#{query},'%') OR nickname LIKE CONCAT('%',#{query},'%') OR CAST(uid AS CHAR)=#{query}";
    @Select("SELECT uid,email,nickname,locale,score,avatar,is_dark_mode,permission,last_sign_at,register_at,verified FROM users" + FILTER + " ORDER BY uid DESC LIMIT 10 OFFSET #{offset}")
    List<Profile> list(String query, long offset);
    @Select("SELECT COUNT(*) FROM users" + FILTER)
    long count(String query);
    @Select("SELECT COUNT(*) FROM users WHERE email=#{email} AND uid<>#{uid}")
    int emailUsed(long uid, String email);
    @Update("UPDATE users SET email=#{email},remember_token=NULL WHERE uid=#{uid}")
    void email(long uid, String email);
    @Update("UPDATE users SET verified=NOT verified,verification_token='' WHERE uid=#{uid}")
    void verification(long uid);
    @Update("UPDATE users SET score=#{score} WHERE uid=#{uid}")
    void score(long uid, int score);
    @Update("UPDATE users SET permission=#{permission},remember_token=NULL WHERE uid=#{uid}")
    void permission(long uid, int permission);
}
