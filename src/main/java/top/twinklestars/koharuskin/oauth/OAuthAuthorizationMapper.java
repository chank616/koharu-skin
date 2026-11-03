package top.twinklestars.koharuskin.oauth;

import org.apache.ibatis.annotations.*;

@Mapper
public interface OAuthAuthorizationMapper {
    record Row(String id,long clientId,Long userId,String fingerprint,String stateHash,String codeHash,String accessHash,String refreshHash,String payload) {}

    @Insert("""
            INSERT INTO koharu_oauth_authorizations(id,client_id,user_id,fingerprint,state_hash,code_hash,access_hash,refresh_hash,payload)
            VALUES(#{id},#{clientId},#{userId},#{fingerprint},#{stateHash},#{codeHash},#{accessHash},#{refreshHash},#{payload})
            ON DUPLICATE KEY UPDATE state_hash=VALUES(state_hash),code_hash=VALUES(code_hash),access_hash=VALUES(access_hash),
            refresh_hash=VALUES(refresh_hash),payload=VALUES(payload)
            """)
    void save(Row row);
    @Select("SELECT * FROM koharu_oauth_authorizations WHERE id=#{id}")
    Row find(String id);
    @Select("SELECT * FROM koharu_oauth_authorizations WHERE id=#{id} FOR UPDATE")
    Row lock(String id);
    @Select("SELECT id FROM koharu_oauth_authorizations WHERE user_id=#{uid} AND access_hash IS NOT NULL")
    java.util.List<String> forUser(long uid);
    @Select("SELECT * FROM koharu_oauth_authorizations WHERE id=#{id} AND user_id=#{uid} FOR UPDATE")
    Row lockOwned(String id,long uid);
    @Select("""
            <script>SELECT * FROM koharu_oauth_authorizations WHERE
            <choose>
              <when test="type=='state'">state_hash=#{hash}</when>
              <when test="type=='code'">code_hash=#{hash}</when>
              <when test="type=='access_token'">access_hash=#{hash}</when>
              <when test="type=='refresh_token'">refresh_hash=#{hash}</when>
              <otherwise>(code_hash=#{hash} OR access_hash=#{hash} OR refresh_hash=#{hash} OR state_hash=#{hash})</otherwise>
            </choose><if test="lock"> FOR UPDATE</if></script>
            """)
    Row findToken(String hash,String type,boolean lock);
    @Delete("DELETE FROM koharu_oauth_authorizations WHERE id=#{id}")
    void remove(String id);

    @Select("SELECT authorities FROM koharu_oauth_consents WHERE client_id=#{clientId} AND principal_name=#{principal}")
    String findConsent(String clientId,String principal);
    @Insert("""
            INSERT INTO koharu_oauth_consents(client_id,principal_name,authorities) VALUES(#{clientId},#{principal},#{authorities})
            ON DUPLICATE KEY UPDATE authorities=VALUES(authorities)
            """)
    void saveConsent(String clientId,String principal,String authorities);
    @Delete("DELETE FROM koharu_oauth_consents WHERE client_id=#{clientId} AND principal_name=#{principal}")
    void removeConsent(String clientId,String principal);
}
