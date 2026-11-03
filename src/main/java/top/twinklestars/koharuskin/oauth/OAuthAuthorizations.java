package top.twinklestars.koharuskin.oauth;

import org.springframework.stereotype.Service;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

@Service
public class OAuthAuthorizations implements OAuth2AuthorizationService {
    private final OAuthAuthorizationMapper rows;
    private final OAuthRegisteredClients clients;
    private final AccountMapper users;
    private final SiteOptions options;
    private final java.time.Clock clock;
    private final JsonMapper json=JsonMapper.builder().addModules(SecurityJacksonModules.getModules(getClass().getClassLoader())).build();
    public OAuthAuthorizations(OAuthAuthorizationMapper rows,OAuthRegisteredClients clients,AccountMapper users,SiteOptions options,java.time.Clock clock) {
        this.rows=rows; this.clients=clients; this.users=users; this.options=options; this.clock=clock;
    }
    record Token(String value,Instant issued,Instant expires,Map<String,Object> metadata,Set<String> scopes) {}
    record Stored(String principal,String grant,Set<String> scopes,Map<String,Object> attributes,Token code,Token access,Token refresh,Token idToken) {}

    @Override public void save(OAuth2Authorization authorization) {
        if(!OAuthScopes.validYgg(authorization.getAuthorizedScopes())) throw new OAuth2AuthenticationException("invalid_scope");
        var attributes=new HashMap<>(authorization.getAttributes());
        attributes.putIfAbsent("koharu.createdAt",clock.instant().toString());
        Authentication principal=(Authentication)attributes.get(Principal.class.getName());
        if(principal!=null&&principal.getPrincipal() instanceof AccountUser user) {
            attributes.put("koharu.uid",Long.toString(user.uid())); attributes.put("koharu.fingerprint",fingerprint(user));
            attributes.put(Principal.class.getName(),UsernamePasswordAuthenticationToken.authenticated(Long.toString(user.uid()),null,user.getAuthorities()));
        }
        var stored=new Stored(authorization.getPrincipalName(),authorization.getAuthorizationGrantType().getValue(),new HashSet<>(authorization.getAuthorizedScopes()),attributes,
                token(authorization.getToken(OAuth2AuthorizationCode.class)),token(authorization.getAccessToken()),token(authorization.getRefreshToken()),
                token(authorization.getToken(org.springframework.security.oauth2.core.oidc.OidcIdToken.class)));
        String uid=(String)attributes.get("koharu.uid");
        rows.save(new OAuthAuthorizationMapper.Row(authorization.getId(),Long.parseLong(authorization.getRegisteredClientId()),uid==null?null:Long.parseLong(uid),
                (String)attributes.get("koharu.fingerprint"),hash((String)attributes.get("state")),tokenHash(stored.code()),tokenHash(stored.access()),tokenHash(stored.refresh()),json.writeValueAsString(stored)));
    }
    private static Token token(OAuth2Authorization.Token<?> token) {
        if(token==null) return null;
        var value=token.getToken();
        return new Token(value.getTokenValue(),value.getIssuedAt(),value.getExpiresAt(),token.getMetadata(),
                value instanceof OAuth2AccessToken access?new HashSet<>(access.getScopes()):new HashSet<>());
    }
    private static String tokenHash(Token token) { return token==null?null:hash(token.value()); }
    public static String hash(String value) {
        if(value==null) return null;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static String fingerprint(AccountUser user) { return hash(user.email()+"\n"+user.password()); }
    @Override public void remove(OAuth2Authorization authorization) { rows.remove(authorization.getId()); }
    @Override public OAuth2Authorization findById(String id) { return read(rows.find(id)); }
    @Override public OAuth2Authorization findByToken(String token,OAuth2TokenType type) {
        var authorization=read(tokenRow(token,type==null?null:type.getValue()));
        if(authorization!=null&&type!=null&&type.getValue().equals("code")&&authorization.getAuthorizedScopes().contains(OAuthScopes.SELECT)
                &&!Boolean.TRUE.equals(authorization.getAttribute("koharu.ygg.ready"))) return null;
        return authorization;
    }
    public OAuth2Authorization selectionCode(String code) { return read(tokenRow(code,"code")); }
    public OAuth2Authorization lockOwned(String id,long uid) { return read(rows.lockOwned(id,uid)); }
    private OAuthAuthorizationMapper.Row tokenRow(String token,String type) {
        String hash=hash(token);
        var row=rows.findToken(hash,type,false);
        // 与撤销操作统一按主键加锁，避免令牌索引与主键之间的反向锁等待。
        if(row!=null&&TransactionSynchronizationManager.isActualTransactionActive()) row=rows.lock(row.id());
        if(row==null) return null;
        boolean matches=switch(Objects.toString(type,"")) {
            case "code" -> hash.equals(row.codeHash());
            case "state" -> hash.equals(row.stateHash());
            case "access_token" -> hash.equals(row.accessHash());
            case "refresh_token" -> hash.equals(row.refreshHash());
            default -> hash.equals(row.codeHash())||hash.equals(row.stateHash())||hash.equals(row.accessHash())||hash.equals(row.refreshHash());
        };
        return matches?row:null;
    }
    private OAuth2Authorization read(OAuthAuthorizationMapper.Row row) {
        if(row==null) return null;
        var client=clients.findById(Long.toString(row.clientId())); if(client==null) return null;
        if(row.userId()!=null) {
            var user=users.findById(row.userId());
            if(user==null||!user.isEnabled()||!fingerprint(user).equals(row.fingerprint())
                    ||(options.enabled("require_verification",false)&&!users.verified(user.uid()))) return null;
        }
        var data=json.readValue(row.payload(),Stored.class);
        if(data.code()==null&&data.access()==null&&Instant.parse((String)data.attributes().get("koharu.createdAt")).plusSeconds(600).isBefore(clock.instant())) return null;
        var builder=OAuth2Authorization.withRegisteredClient(client).id(row.id()).principalName(data.principal())
                .authorizationGrantType(new AuthorizationGrantType(data.grant())).authorizedScopes(data.scopes()).attributes(attrs->attrs.putAll(data.attributes()));
        if(data.code()!=null) { var t=data.code(); builder.token(new OAuth2AuthorizationCode(t.value(),t.issued(),t.expires()),m->m.putAll(t.metadata())); }
        if(data.access()!=null) { var t=data.access(); builder.token(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,t.value(),t.issued(),t.expires(),t.scopes()),m->m.putAll(t.metadata())); }
        if(data.refresh()!=null) { var t=data.refresh(); builder.token(new OAuth2RefreshToken(t.value(),t.issued(),t.expires()),m->m.putAll(t.metadata())); }
        if(data.idToken()!=null) { var t=data.idToken(); builder.token(new org.springframework.security.oauth2.core.oidc.OidcIdToken(t.value(),t.issued(),t.expires(),
                (Map<String,Object>)t.metadata().get(OAuth2Authorization.Token.CLAIMS_METADATA_NAME)),m->m.putAll(t.metadata())); }
        return builder.build();
    }
}
