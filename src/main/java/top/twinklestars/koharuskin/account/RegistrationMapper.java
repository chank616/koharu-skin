package top.twinklestars.koharuskin.account;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RegistrationMapper {
    @Select("SELECT COUNT(*) FROM users WHERE ip = #{ip}")
    int countIp(String ip);

    @Select("SELECT COUNT(*) FROM players WHERE name = #{name}")
    int countName(String name);

    @Insert("""
            INSERT INTO users (email,nickname,password,score,ip,permission,register_at,last_sign_at)
            VALUES (#{email},#{name},#{password},#{score},#{ip},0,CURRENT_TIMESTAMP,#{lastSign})
            """)
    void create(String email, String name, String password, int score, String ip, java.time.LocalDateTime lastSign);

    @Insert("INSERT INTO players (uid,name,tid_skin,tid_cape,last_modified) VALUES (#{uid},#{name},0,0,CURRENT_TIMESTAMP)")
    void createPlayer(long uid, String name);
}
