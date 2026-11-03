package top.twinklestars.koharuskin.yggdrasil;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface UnionMapper {
    @Select("SELECT u.name,u.uuid,p.uid FROM uuid u LEFT JOIN players p ON p.name=u.name ORDER BY u.name FOR UPDATE")
    List<Mapping> mappings();
    @Update("UPDATE uuid SET uuid=#{uuid} WHERE name=#{name}")
    void remap(String name,String uuid);
    @Select("SELECT u.email FROM players p JOIN users u ON u.uid=p.uid WHERE p.name=#{name} ORDER BY p.pid LIMIT 1")
    String email(String name);
    @Delete("DELETE FROM koharu_union_nonces WHERE expires_at<=#{now}")
    void expireNonces(long now);
    @Insert("INSERT IGNORE INTO koharu_union_nonces(nonce,expires_at) VALUES(#{nonce},#{expires})")
    int claimNonce(String nonce,long expires);
    record Mapping(String name,String uuid,Long uid) {}
}
