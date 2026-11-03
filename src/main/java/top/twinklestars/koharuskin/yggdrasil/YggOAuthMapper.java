package top.twinklestars.koharuskin.yggdrasil;

import org.apache.ibatis.annotations.*;

@Mapper
public interface YggOAuthMapper {
    record Token(String tokenHash,String source,String tokenId,long userUid,String profileUuid,String profileName,
                 String credentialsHash,long issuedAt,long expiresAt) {}
    @Insert("""
            INSERT INTO koharu_ygg_oauth_tokens(token_hash,source,token_id,user_uid,profile_uuid,profile_name,credentials_hash,issued_at,expires_at)
            VALUES(#{tokenHash},#{source},#{tokenId},#{userUid},#{profileUuid},#{profileName},#{credentialsHash},#{issuedAt},#{expiresAt})
            ON DUPLICATE KEY UPDATE expires_at=VALUES(expires_at)
            """)
    void save(Token token);
    @Select("SELECT * FROM koharu_ygg_oauth_tokens WHERE token_hash=#{hash}")
    Token find(String hash);
    @Delete("DELETE FROM koharu_ygg_oauth_tokens WHERE expires_at<=#{now}")
    void clean(long now);
}
