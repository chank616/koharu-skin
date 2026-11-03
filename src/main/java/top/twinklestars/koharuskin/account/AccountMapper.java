package top.twinklestars.koharuskin.account;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AccountMapper {
    @Select("SELECT uid, email, nickname, password, permission FROM users WHERE email = #{email} LIMIT 1")
    AccountUser findByEmail(String email);

    @Select("""
            SELECT u.uid, u.email, u.nickname, u.password, u.permission
            FROM users u JOIN players p ON p.uid = u.uid WHERE p.name = #{name} LIMIT 1
            """)
    AccountUser findByPlayerName(String name);

    @Select("SELECT uid, email, nickname, password, permission FROM users WHERE uid = #{uid}")
    AccountUser findById(long uid);

    @Select("SELECT uid,email,nickname,password,permission FROM users WHERE uid=#{uid} FOR UPDATE")
    AccountUser lockById(long uid);

    @Select("SELECT verified FROM users WHERE uid=#{uid}")
    boolean verified(long uid);

    @Update("UPDATE users SET verified=true,verification_token='' WHERE uid=#{uid}")
    void verify(long uid);

    @Update("UPDATE users SET password=#{password},remember_token=NULL WHERE uid=#{uid}")
    void password(long uid, String password);
}
