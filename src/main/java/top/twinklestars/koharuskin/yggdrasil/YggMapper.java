package top.twinklestars.koharuskin.yggdrasil;

import org.apache.ibatis.annotations.*;
import top.twinklestars.koharuskin.player.Player;
import java.util.List;

@Mapper
public interface YggMapper {
    @Select("SELECT token_hash,user_uid,client_token,player_pid,profile_name,credentials_hash,issued_at,valid_until,expires_at FROM koharu_ygg_tokens WHERE token_hash=#{hash}")
    Token find(String hash);
    @Select("SELECT token_hash,user_uid,client_token,player_pid,profile_name,credentials_hash,issued_at,valid_until,expires_at FROM koharu_ygg_tokens WHERE token_hash=#{hash} FOR UPDATE")
    Token lock(String hash);
    @Insert("""
            INSERT INTO koharu_ygg_tokens(token_hash,user_uid,client_token,player_pid,profile_name,credentials_hash,issued_at,valid_until,expires_at)
            VALUES(#{tokenHash},#{userUid},#{clientToken},#{playerPid},#{profileName},#{credentialsHash},#{issuedAt},#{validUntil},#{expiresAt})
            """)
    void insert(Token token);
    @Delete("DELETE FROM koharu_ygg_tokens WHERE token_hash=#{hash}")
    void delete(String hash);
    @Delete("DELETE FROM koharu_ygg_tokens WHERE user_uid=#{uid}")
    void revokeUser(long uid);
    @Select("SELECT token_hash FROM koharu_ygg_tokens WHERE user_uid=#{uid} ORDER BY issued_at,token_hash")
    List<String> userTokens(long uid);
    @Delete("DELETE FROM koharu_ygg_tokens WHERE expires_at<=#{now}")
    void deleteExpiredTokens(long now);
    @Delete("DELETE FROM koharu_ygg_sessions WHERE expires_at<=#{now}")
    void deleteExpiredSessions(long now);
    @Insert("""
            INSERT INTO koharu_ygg_sessions(server_hash,token_hash,client_ip,expires_at) VALUES(#{serverHash},#{tokenHash},#{clientIp},#{expiresAt})
            ON DUPLICATE KEY UPDATE token_hash=#{tokenHash},client_ip=#{clientIp},expires_at=#{expiresAt}
            """)
    void join(Join session);
    @Select("SELECT server_hash,token_hash,client_ip,expires_at FROM koharu_ygg_sessions WHERE server_hash=#{hash}")
    Join session(String hash);
    @Select("""
            SELECT p.pid,p.uid,p.name,p.tid_skin,p.tid_cape,p.last_modified FROM players p JOIN uuid u ON p.name=u.name
            WHERE REPLACE(u.uuid,'-','')=#{uuid} ORDER BY p.pid LIMIT 1
            """)
    Player profile(String uuid);

    record Token(String tokenHash, long userUid, String clientToken, Long playerPid, String profileName,
                 String credentialsHash, long issuedAt, long validUntil, long expiresAt) {}
    record Join(String serverHash, String tokenHash, String clientIp, long expiresAt) {}
}
