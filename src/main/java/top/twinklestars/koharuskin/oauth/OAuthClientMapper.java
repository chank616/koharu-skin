package top.twinklestars.koharuskin.oauth;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface OAuthClientMapper {
    String COLUMNS="id,user_id,name,secret,provider,redirect,personal_access_client,password_client,revoked,created_at,updated_at";

    @Select("SELECT "+COLUMNS+" FROM oauth_clients WHERE user_id=#{uid} AND revoked=false ORDER BY name,id")
    List<OAuthClient> list(long uid);

    @Select("SELECT "+COLUMNS+" FROM oauth_clients WHERE id=#{id}")
    OAuthClient find(long id);

    @Select("SELECT "+COLUMNS+" FROM oauth_clients WHERE id=#{id} AND user_id=#{uid} FOR UPDATE")
    OAuthClient lockOwned(long id,long uid);

    @Insert("""
            INSERT INTO oauth_clients(user_id,name,secret,redirect,personal_access_client,password_client,revoked,created_at,updated_at)
            VALUES(#{uid},#{name},#{secret},#{redirect},false,false,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys=true,keyProperty="id")
    void create(Map<String,Object> client);

    @Update("UPDATE oauth_clients SET name=#{name},redirect=#{redirect},updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    void update(long id,String name,String redirect);

    @Update("UPDATE oauth_clients SET revoked=true,updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    void revoke(long id);

    @Update("UPDATE oauth_access_tokens SET revoked=true,updated_at=CURRENT_TIMESTAMP WHERE client_id=#{id}")
    void revokeAccessTokens(long id);

    @Update("UPDATE oauth_auth_codes SET revoked=true WHERE client_id=#{id}")
    void revokeCodes(long id);

    @Update("UPDATE oauth_refresh_tokens SET revoked=true WHERE access_token_id IN (SELECT id FROM oauth_access_tokens WHERE client_id=#{id})")
    void revokeRefreshTokens(long id);

    @Select("SELECT name AS id,description FROM scopes ORDER BY scopes.id")
    List<OAuthScopes.Scope> scopes();
}
