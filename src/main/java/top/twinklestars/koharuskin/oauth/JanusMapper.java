package top.twinklestars.koharuskin.oauth;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface JanusMapper {
    record Token(String id,long userId,long clientId,String scopes,LocalDateTime createdAt,LocalDateTime expiresAt) {}
    @Select("""
            SELECT t.id,t.user_id,t.client_id,t.scopes,t.created_at,t.expires_at FROM oauth_access_tokens t
            JOIN oauth_clients c ON c.id=t.client_id
            WHERE t.id=#{id} AND t.revoked=false AND c.revoked=false AND t.expires_at>CURRENT_TIMESTAMP
            """)
    Token find(String id);
    @Update("UPDATE oauth_access_tokens SET revoked=true,updated_at=CURRENT_TIMESTAMP WHERE user_id=#{uid}")
    void revokeAccess(long uid);
    @Update("UPDATE oauth_refresh_tokens SET revoked=true WHERE access_token_id IN (SELECT id FROM oauth_access_tokens WHERE user_id=#{uid})")
    void revokeRefresh(long uid);
    @Delete("DELETE FROM code_id_to_uuid WHERE code_id IN (SELECT id FROM oauth_auth_codes WHERE user_id=#{uid})")
    void revokeSelections(long uid);
    @Delete("DELETE FROM oauth_auth_codes WHERE user_id=#{uid}")
    void revokeGrants(long uid);
    default void revokeUser(long uid) {
        revokeRefresh(uid); revokeAccess(uid);
        // Janus 刷新时还会查询此授权记录；只撤销 Passport 的刷新令牌不足以停止刷新。
        revokeSelections(uid); revokeGrants(uid);
    }
    @Select("SELECT changed_at FROM koharu_player_renames WHERE uuid=#{uuid}")
    Long renamedAt(String uuid);
    @Insert("INSERT INTO koharu_player_renames(uuid,changed_at) VALUES(#{uuid},#{changedAt}) ON DUPLICATE KEY UPDATE changed_at=#{changedAt}")
    void renamed(String uuid,long changedAt);
}
