package top.twinklestars.koharuskin.oauth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.*;
import top.twinklestars.koharuskin.account.AccountUser;
import java.util.*;

@Service
public class OAuthConsentPage {
    private final OAuthAuthorizations authorizations;
    private final OAuthRegisteredClients clients;
    private final OAuthScopes scopes;
    private final OAuthConsents consents;
    public OAuthConsentPage(OAuthAuthorizations authorizations,OAuthRegisteredClients clients,OAuthScopes scopes,OAuthConsents consents) {
        this.authorizations=authorizations; this.clients=clients; this.scopes=scopes; this.consents=consents;
    }
    public record Page(String clientId,String clientName,String state,List<Permission> permissions) {}
    public record Permission(String id,String description,boolean granted) {}
    public Page show(AccountUser user,String clientId,String state) {
        var authorization=pending(user,state);
        if(!authorization.getRegisteredClientId().equals(clientId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        OAuth2AuthorizationRequest request=authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        var consent=consents.findById(clientId,Long.toString(user.uid()));
        var descriptions=new HashMap<String,String>(); scopes.list().forEach(scope->descriptions.put(scope.id(),scope.description()));
        var permissions=request.getScopes().stream().sorted().map(scope->new Permission(scope,descriptions.getOrDefault(scope,scope),consent!=null&&consent.getScopes().contains(scope))).toList();
        return new Page(clientId,clients.findById(clientId).getClientName(),state,permissions);
    }
    @Transactional
    public String deny(AccountUser user,String state) {
        var authorization=pending(user,state);
        OAuth2AuthorizationRequest request=authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        authorizations.remove(authorization);
        String uri=request.getRedirectUri();
        if(uri==null) uri=clients.findById(authorization.getRegisteredClientId()).getRedirectUris().iterator().next();
        var redirect=UriComponentsBuilder.fromUriString(uri).queryParam("error","access_denied");
        if(request.getState()!=null) redirect.queryParam("state",request.getState());
        return redirect.build().encode().toUriString();
    }
    private OAuth2Authorization pending(AccountUser user,String state) {
        var authorization=authorizations.findByToken(state,new OAuth2TokenType("state"));
        if(authorization==null||!authorization.getPrincipalName().equals(Long.toString(user.uid()))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"授权请求已失效");
        return authorization;
    }
}
