package top.twinklestars.koharuskin.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Service
public class OAuthGrants {
    private final OAuthAuthorizationMapper rows;
    private final OAuthAuthorizations authorizations;
    private final OAuthClientMapper clients;
    public OAuthGrants(OAuthAuthorizationMapper rows,OAuthAuthorizations authorizations,OAuthClientMapper clients) {
        this.rows=rows; this.authorizations=authorizations; this.clients=clients;
    }
    public record Client(long id,String name,String redirect) {}
    public record Grant(String id,@JsonProperty("user_id") long userId,@JsonProperty("client_id") long clientId,String name,
                        Set<String> scopes,boolean revoked,@JsonProperty("created_at") Instant createdAt,
                        @JsonProperty("updated_at") Instant updatedAt,@JsonProperty("expires_at") Instant expiresAt,Client client) {}
    public List<Grant> list(long uid) {
        var result=new ArrayList<Grant>();
        for(String id:rows.forUser(uid)) {
            var authorization=authorizations.findById(id);
            if(authorization==null||authorization.getAccessToken().isInvalidated()) continue;
            var client=clients.find(Long.parseLong(authorization.getRegisteredClientId()));
            if(client.personalAccessClient()||client.passwordClient()) continue;
            var token=authorization.getAccessToken().getToken();
            result.add(new Grant(id,uid,client.id(),null,token.getScopes(),false,Instant.parse(authorization.getAttribute("koharu.createdAt")),
                    token.getIssuedAt(),token.getExpiresAt(),new Client(client.id(),client.name(),client.redirect())));
        }
        result.sort(Comparator.comparing(Grant::createdAt).reversed());
        return result;
    }
    @Transactional
    public void revoke(long uid,String id) {
        var row=rows.lockOwned(id,uid);
        if(row==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        rows.remove(id);
        rows.removeConsent(Long.toString(row.clientId()),Long.toString(uid));
    }
}
