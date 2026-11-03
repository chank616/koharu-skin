package top.twinklestars.koharuskin.oauth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.account.BusinessException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;

@Service
public class OAuthClients {
    private final OAuthClientMapper clients;
    private final JanusMapper legacy;
    private final SecureRandom random=new SecureRandom();
    public OAuthClients(OAuthClientMapper clients,JanusMapper legacy) { this.clients=clients;this.legacy=legacy; }

    public List<OAuthClient> list(long uid) { return clients.list(uid); }

    @Transactional
    public OAuthClient create(long uid,String name,String redirect,boolean confidential) {
        validateRedirect(redirect);
        var values=new HashMap<String,Object>();
        values.put("uid",uid); values.put("name",name); values.put("redirect",redirect);
        byte[] secret=new byte[24]; random.nextBytes(secret);
        values.put("secret",confidential?HexFormat.of().formatHex(secret):null);
        clients.create(values);
        return clients.find(((Number)values.get("id")).longValue());
    }

    @Transactional
    public OAuthClient update(long uid,long id,String name,String redirect) {
        var client=owned(uid,id);
        if(client.revoked()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        validateRedirect(redirect);
        clients.update(id,name,redirect);
        return clients.find(id);
    }

    @Transactional
    public void revoke(long uid,long id) {
        owned(uid,id);
        clients.revoke(id);
        clients.revokeAccessTokens(id);
        clients.revokeRefreshTokens(id);
        clients.revokeCodes(id);
        // Janus 刷新时仍使用已消费的授权码，必须移除对应授权才能停止续期。
        legacy.revokeClientSelections(id);
        legacy.revokeClientGrants(id);
    }

    private OAuthClient owned(long uid,long id) {
        var client=clients.lockOwned(id,uid);
        if(client==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return client;
    }

    private static void validateRedirect(String redirects) {
        for(String value:redirects.split(",",-1)) {
            URI uri;
            try { uri=new URI(value); }
            catch(URISyntaxException error) { throw new BusinessException("回调地址格式不正确"); }
            if(uri.getScheme()==null || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null
                    || uri.getPort()>65535 || uri.getRawAuthority().contains("*")
                    || List.of("javascript","data","file").contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))) {
                throw new BusinessException("请填写完整的回调地址，多个地址用英文逗号分隔，不允许通配符或 URL 片段");
            }
        }
    }
}
