package top.twinklestars.koharuskin.account;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ProfileMapper {
    @Select("SELECT uid,email,nickname,locale,score,avatar,is_dark_mode,permission,last_sign_at,register_at,verified FROM users WHERE uid=#{uid}")
    Profile find(long uid);
    @Update("UPDATE users SET nickname=#{nickname} WHERE uid=#{uid}")
    void nickname(long uid, String nickname);
    @Update("UPDATE users SET email=#{email},verified=false,verification_token='',remember_token=NULL WHERE uid=#{uid}")
    void email(long uid, String email);
    @Delete("DELETE FROM players WHERE uid=#{uid}")
    void deletePlayers(long uid);
    @Delete("DELETE FROM users WHERE uid=#{uid}")
    void delete(long uid);
    @Update("UPDATE users SET is_dark_mode=NOT is_dark_mode WHERE uid=#{uid}")
    void toggleDarkMode(long uid);

    record Profile(long uid, String email, String nickname, String locale, int score, int avatar,
                   @JsonProperty("is_dark_mode") boolean isDarkMode, int permission,
                   @JsonProperty("last_sign_at") LocalDateTime lastSignAt,
                   @JsonProperty("register_at") LocalDateTime registerAt, boolean verified) {}
}
