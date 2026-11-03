package top.twinklestars.koharuskin.setup;

import org.apache.ibatis.annotations.*;

@Mapper
public interface SetupMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM users)")
    boolean hasUsers();
    @Insert("""
            INSERT INTO users(email,nickname,password,score,ip,permission,register_at,last_sign_at,verified)
            VALUES(#{email},#{nickname},#{password},#{score},#{ip},2,CURRENT_TIMESTAMP,#{lastSign},true)
            """)
    void administrator(String email,String nickname,String password,int score,String ip,java.time.LocalDateTime lastSign);
}
