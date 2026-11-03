package top.twinklestars.koharuskin.oauth;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.player.PlayerMapper;
import top.twinklestars.koharuskin.site.SiteOptions;
import top.twinklestars.koharuskin.yggdrasil.*;
import java.util.*;

@Service
public class YggConnect {
    private final OAuthAuthorizations authorizations;
    private final OAuthRegisteredClients clients;
    private final PlayerMapper players;
    private final YggMapper ygg;
    private final YggProfiles profiles;
    private final AccountMapper accounts;
    private final OfficialExtensions extensions;
    private final SiteOptions options;
    private final top.twinklestars.koharuskin.site.SiteUrls urls;
    private final YggConnectMapper legacy;
    private final tools.jackson.databind.json.JsonMapper json;
    public YggConnect(OAuthAuthorizations authorizations,OAuthRegisteredClients clients,
                      PlayerMapper players,YggMapper ygg,YggProfiles profiles,AccountMapper accounts,OfficialExtensions extensions,
                      SiteOptions options,top.twinklestars.koharuskin.site.SiteUrls urls,YggConnectMapper legacy,tools.jackson.databind.json.JsonMapper json) {
        this.authorizations=authorizations; this.clients=clients; this.players=players; this.ygg=ygg;
        this.profiles=profiles; this.accounts=accounts; this.extensions=extensions; this.options=options; this.urls=urls;
        this.legacy=legacy; this.json=json;
    }
    public record Selection(String codeId,String state,String clientName,List<Map<String,String>> profiles) {}
    public record Callback(Selection selection,String redirect) {}
    public void requireEnabled() {
        if(!extensions.enabled("yggdrasil-connect")) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"联合认证扩展未启用");
    }
    public String callbackUri() { return urls.publicUrl()+"/yggc/callback"; }
    public String issuer() {
        requireEnabled();
        String value=options.text("ygg_connect_server_url","").replaceAll("/+$","");
        if(value.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"请先配置 OIDC 服务地址");
        var uri=java.net.URI.create(value);
        if(!Set.of("http","https").contains(Objects.toString(uri.getScheme(),""))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"OIDC 服务地址无效");
        return value;
    }
    @Transactional
    public Callback callback(AccountUser user,String code,String state) {
        issuer();
        var authorization=authorizations.selectionCode(code);
        check(user,authorization,state);
        if(authorization.getAuthorizedScopes().contains(OAuthScopes.SELECT))
            return new Callback(selection(authorization,state),null);
        authorizations.save(OAuth2Authorization.from(authorization).attribute("koharu.ygg.ready",true).build());
        publish(authorization,null);
        return new Callback(null,redirect(state,"code",authorization.getId()));
    }
    @Transactional
    public Selection show(AccountUser user,String codeId,String state) { return selection(owned(user,codeId,state),state); }
    private Selection selection(OAuth2Authorization authorization,String state) {
        if(!authorization.getAuthorizedScopes().contains(OAuthScopes.SELECT)) throw invalid();
        long uid=Long.parseLong(authorization.getAttribute("koharu.uid"));
        return new Selection(authorization.getId(),state,clients.findById(authorization.getRegisteredClientId()).getClientName(),
                players.list(uid).stream().map(profiles::simple).toList());
    }
    @Transactional
    public String select(AccountUser user,String codeId,String state,String uuid) {
        var authorization=owned(user,codeId,state);
        if(!authorization.getAuthorizedScopes().contains(OAuthScopes.SELECT)) throw invalid();
        var player=ygg.profile(uuid);
        if(player==null||player.uid()!=user.uid()) throw invalid();
        authorizations.save(OAuth2Authorization.from(authorization).attribute("koharu.ygg.selected",uuid)
                .attribute("koharu.ygg.name",player.name()).attribute("koharu.ygg.ready",true).build());
        publish(authorization,uuid);
        return redirect(state,"code",authorization.getId());
    }
    private void publish(OAuth2Authorization authorization,String uuid) {
        legacy.grant(authorization.getId(),Long.parseLong(authorization.getAttribute("koharu.uid")),Long.parseLong(authorization.getRegisteredClientId()),
                json.writeValueAsString(authorization.getAuthorizedScopes()),java.time.LocalDateTime.ofInstant(
                        authorization.getToken(OAuth2AuthorizationCode.class).getToken().getExpiresAt(),java.time.ZoneId.systemDefault()));
        if(uuid!=null) legacy.select(authorization.getId(),uuid);
    }
    @Transactional
    public String cancel(AccountUser user,String codeId,String state) {
        var authorization=owned(user,codeId,state); authorizations.remove(authorization);
        return redirect(state,"error","access_denied");
    }
    private OAuth2Authorization owned(AccountUser user,String id,String state) {
        issuer();
        var authorization=authorizations.lockOwned(id,user.uid()); check(user,authorization,state); return authorization;
    }
    private void check(AccountUser user,OAuth2Authorization authorization,String state) {
        if(authorization==null||!Long.toString(user.uid()).equals(authorization.getAttribute("koharu.uid"))
                ||authorization.getToken(OAuth2AuthorizationCode.class)==null||!authorization.getToken(OAuth2AuthorizationCode.class).isActive()
                ||Boolean.TRUE.equals(authorization.getAttribute("koharu.ygg.ready"))) throw invalid();
        OAuth2AuthorizationRequest request=authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        if(!callbackUri().equals(request.getRedirectUri())||state.isBlank()||!state.equals(request.getState())
                ||!authorization.getAuthorizedScopes().contains("openid")) throw invalid();
    }
    public String redirect(String state,String key,String value) {
        if(state.isBlank()||state.length()>512) throw invalid();
        return UriComponentsBuilder.fromUriString(issuer()).pathSegment("interaction",state,"callback")
                .queryParam(key,value).queryParam("state",state).build().encode().toUriString();
    }
    public Map<String,Object> userInfo(AccountUser user,String authorizationId) {
        requireEnabled();
        var authorization=authorizations.findById(authorizationId);
        if(authorization==null||authorization.getAccessToken()==null||!authorization.getAccessToken().isActive())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var scopes=authorization.getAccessToken().getToken().getScopes();
        return userInfo(user,scopes,authorization.getAttribute("koharu.ygg.selected"));
    }
    public Map<String,Object> userInfo(AccountUser user,Set<String> scopes,String selected) {
        requireEnabled();
        var result=new LinkedHashMap<String,Object>(); result.put("sub",Long.toString(user.uid()));
        if(scopes.contains("profile")) { result.put("nickname",user.nickname()); result.put("picture",urls.publicUrl()+"/avatar/user/"+user.uid()); }
        if(scopes.contains("email")) { result.put("email",user.email()); result.put("email_verified",accounts.verified(user.uid())); }
        if(scopes.contains(OAuthScopes.SELECT)) {
            String uuid=selected;
            var player=uuid==null?null:ygg.profile(uuid);
            if(player==null||player.uid()!=user.uid()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"所选角色已失效");
            result.put("selectedProfile",Map.of("id",uuid,"name",player.name()));
        }
        if(scopes.contains(OAuthScopes.READ)) result.put("availableProfiles",players.list(user.uid()).stream().map(profiles::simple).toList());
        return result;
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"授权请求已失效或角色不属于当前账号"); }
}
