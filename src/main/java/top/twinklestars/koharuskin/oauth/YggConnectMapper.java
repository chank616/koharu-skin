package top.twinklestars.koharuskin.oauth;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface YggConnectMapper {
    @Insert("INSERT INTO oauth_auth_codes(id,user_id,client_id,scopes,revoked,expires_at) VALUES(#{id},#{uid},#{clientId},#{scopes},false,#{expires})")
    void grant(String id,long uid,long clientId,String scopes,LocalDateTime expires);
    @Insert("INSERT INTO code_id_to_uuid(code_id,uuid) VALUES(#{id},#{uuid})")
    void select(String id,String uuid);
}
