package top.twinklestars.koharuskin.oauth;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface JanusMapper {
    record Token(String id,long userId,long clientId,String scopes,LocalDateTime createdAt,LocalDateTime expiresAt) {}
    record Grant(String id,long clientId,String name,String scopes,LocalDateTime createdAt,LocalDateTime updatedAt,LocalDateTime expiresAt) {}
    @Select("""
            SELECT t.id,t.client_id,t.name,COALESCE(t.scopes,'[]') AS scopes,t.created_at,t.updated_at,t.expires_at FROM oauth_access_tokens t
            JOIN oauth_clients c ON c.id=t.client_id
            WHERE t.user_id=#{uid} AND t.revoked=false AND c.revoked=false
              AND c.personal_access_client=false AND c.password_client=false
            """)
    java.util.List<Grant> grants(long uid);
    @Select("SELECT id,user_id,client_id,scopes,created_at,expires_at FROM oauth_access_tokens WHERE id=#{id} AND user_id=#{uid} FOR UPDATE")
    Token lockOwned(String id,long uid);
    @Select("""
            SELECT t.id,t.user_id,t.client_id,t.scopes,t.created_at,t.expires_at FROM oauth_access_tokens t
            JOIN oauth_clients c ON c.id=t.client_id
            WHERE t.id=#{id} AND t.revoked=false AND c.revoked=false AND t.expires_at>CURRENT_TIMESTAMP
            """)
    Token find(String id);
    @Select("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_schema)=LOWER(SCHEMA()) AND LOWER(table_name)='yggc_refresh_tokens'")
    int hasRefreshStore();
    @Delete("DELETE FROM yggc_refresh_tokens WHERE id IN (SELECT id FROM oauth_refresh_tokens WHERE access_token_id=#{id})")
    void revokeJanusRefresh(String id);
    @Update("UPDATE oauth_refresh_tokens SET revoked=true WHERE access_token_id=#{id}")
    void revokeTokenRefresh(String id);
    @Update("UPDATE oauth_access_tokens SET revoked=true,updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    void revokeTokenAccess(String id);
    default void revokeToken(String id) {
        // 未部署独立 Janus 服务的旧站没有此表。
        if(hasRefreshStore()>0) revokeJanusRefresh(id);
        revokeTokenRefresh(id);revokeTokenAccess(id);
    }
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
    @Delete("DELETE FROM code_id_to_uuid WHERE code_id IN (SELECT id FROM oauth_auth_codes WHERE client_id=#{id})")
    void revokeClientSelections(long id);
    @Delete("DELETE FROM oauth_auth_codes WHERE client_id=#{id}")
    void revokeClientGrants(long id);
    @Select("SELECT changed_at FROM koharu_player_renames WHERE uuid=#{uuid}")
    Long renamedAt(String uuid);
    @Insert("INSERT INTO koharu_player_renames(uuid,changed_at) VALUES(#{uuid},#{changedAt}) ON DUPLICATE KEY UPDATE changed_at=#{changedAt}")
    void renamed(String uuid,long changedAt);
}
