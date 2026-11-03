package top.twinklestars.koharuskin.yggdrasil;

import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.stereotype.Service;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.oauth.*;
import top.twinklestars.koharuskin.player.Player;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.util.Set;

@Service
public class YggOAuth {
    private final OAuthAuthorizations authorizations;
    private final OAuthAuthorizationMapper grants;
    private final JanusTokens janus;
    private final JanusMapper legacy;
    private final YggOAuthMapper rows;
    private final YggMapper profiles;
    private final AccountMapper accounts;
    private final OfficialExtensions extensions;
    private final SiteOptions options;
    private final JsonMapper json;
    private final Clock clock;
    public YggOAuth(OAuthAuthorizations authorizations,OAuthAuthorizationMapper grants,JanusTokens janus,JanusMapper legacy,
                    YggOAuthMapper rows,YggMapper profiles,AccountMapper accounts,OfficialExtensions extensions,SiteOptions options,JsonMapper json,Clock clock) {
        this.authorizations=authorizations;this.grants=grants;this.janus=janus;this.legacy=legacy;this.rows=rows;
        this.profiles=profiles;this.accounts=accounts;this.extensions=extensions;this.options=options;this.json=json;this.clock=clock;
    }
    public YggOAuthMapper.Token read(String value) {
        if(!extensions.enabled("yggdrasil-connect")) throw YggException.invalid();
        String source,id,uuid; AccountUser user; long issued,expires; Set<String> scopes;
        if(value.contains(".")) {
            JanusTokens.Access access;
            try { access=janus.read(value); } catch(BadOpaqueTokenException error) { throw YggException.invalid(); }
            source="janus";id=access.id();uuid=access.selected();user=access.user();issued=access.issuedAt();expires=access.expiresAt();scopes=access.scopes();
        } else {
            var grant=authorizations.findByToken(value,OAuth2TokenType.ACCESS_TOKEN);
            if(grant==null||grant.getAccessToken()==null||!grant.getAccessToken().isActive()) throw YggException.invalid();
            var access=grant.getAccessToken().getToken();
            source="oauth";id=grant.getId();uuid=grant.getAttribute("koharu.ygg.selected");
            user=accounts.findById(Long.parseLong(grant.getAttribute("koharu.uid")));
            issued=access.getIssuedAt().toEpochMilli();expires=access.getExpiresAt().getEpochSecond();scopes=access.getScopes();
        }
        if(!scopes.containsAll(Set.of(OAuthScopes.SELECT,OAuthScopes.JOIN))||uuid==null) throw YggException.invalid();
        var player=profiles.profile(uuid);
        Long renamed=legacy.renamedAt(uuid);
        if(player==null||player.uid()!=user.uid()||(renamed!=null&&renamed>issued)) throw YggException.invalid();
        return new YggOAuthMapper.Token(YggAuth.hash(value),source,id,user.uid(),uuid,player.name(),fingerprint(user),issued,expires);
    }
    public void remember(YggOAuthMapper.Token token) {
        long now=clock.instant().getEpochSecond();rows.clean(now);
        // 只保存进服验证所需的引用；不保存 OAuth / JWT 原始令牌。
        rows.save(new YggOAuthMapper.Token(token.tokenHash(),token.source(),token.tokenId(),token.userUid(),token.profileUuid(),token.profileName(),
                token.credentialsHash(),token.issuedAt(),Math.min(token.expiresAt(),now+120)));
    }
    public Player joined(String hash) {
        var token=rows.find(hash);
        if(token==null||token.expiresAt()<=clock.instant().getEpochSecond()||!extensions.enabled("yggdrasil-connect")) return null;
        var user=accounts.findById(token.userUid());
        if(user==null||!user.isEnabled()||!fingerprint(user).equals(token.credentialsHash())
                ||(options.enabled("require_verification",false)&&!accounts.verified(user.uid()))) return null;
        if(token.source().equals("janus")) {
            var current=legacy.find(token.tokenId());
            if(current==null||current.userId()!=token.userUid()) return null;
            var scopes=json.readTree(current.scopes());
            if(scopes.valueStream().noneMatch(s->s.asString().equals(OAuthScopes.SELECT))
                    ||scopes.valueStream().noneMatch(s->s.asString().equals(OAuthScopes.JOIN))) return null;
        } else {
            var current=authorizations.findById(token.tokenId());
            if(current==null||current.getAccessToken()==null||!current.getAccessToken().isActive()
                    ||!YggAuth.hash(current.getAccessToken().getToken().getTokenValue()).equals(hash)
                    ||!current.getAccessToken().getToken().getScopes().containsAll(Set.of(OAuthScopes.SELECT,OAuthScopes.JOIN))
                    ||!token.profileUuid().equals(current.getAttribute("koharu.ygg.selected"))) return null;
        }
        var player=profiles.profile(token.profileUuid());Long renamed=legacy.renamedAt(token.profileUuid());
        return player!=null&&player.uid()==token.userUid()&&player.name().equals(token.profileName())
                &&(renamed==null||renamed<=token.issuedAt())?player:null;
    }
    public void invalidate(String value) {
        if(value.contains(".")) {
            try { legacy.revokeToken(janus.read(value).id()); } catch(BadOpaqueTokenException ignored) { /* 协议要求无效令牌也返回 204。 */ }
        } else {
            var grant=authorizations.findByToken(value,OAuth2TokenType.ACCESS_TOKEN);
            if(grant!=null) authorizations.remove(grant);
        }
    }
    public void signout(long uid) {
        legacy.revokeUser(uid);
        for(String id:grants.forUser(uid)) {
            var grant=authorizations.lockOwned(id,uid);
            if(grant!=null&&grant.getAuthorizedScopes().contains(OAuthScopes.JOIN)) authorizations.remove(grant);
        }
    }
    private static String fingerprint(AccountUser user) { return YggAuth.hash(user.email()+"\n"+user.password()); }
}
