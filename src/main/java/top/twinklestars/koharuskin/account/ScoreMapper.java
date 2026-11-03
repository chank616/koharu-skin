package top.twinklestars.koharuskin.account;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ScoreMapper {
    record Balance(int score, LocalDateTime lastSignAt) {}
    @Select("SELECT score,last_sign_at FROM users WHERE uid=#{uid}")
    Balance balance(long uid);
    @Select("SELECT score,last_sign_at FROM users WHERE uid=#{uid} FOR UPDATE")
    Balance lock(long uid);
    @Update("UPDATE users SET score=score+#{amount},last_sign_at=#{now} WHERE uid=#{uid}")
    void sign(long uid, int amount, LocalDateTime now);
    @Select("SELECT COUNT(*) FROM players WHERE uid=#{uid}")
    int players(long uid);
    @Select("SELECT COALESCE(SUM(size),0) FROM textures WHERE uploader=#{uid}")
    int storage(long uid);
}
