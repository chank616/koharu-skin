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
    private final JanusMapper legacy;
    private final tools.jackson.databind.json.JsonMapper json;
    public OAuthGrants(OAuthAuthorizationMapper rows,OAuthAuthorizations authorizations,OAuthClientMapper clients,
                       JanusMapper legacy,tools.jackson.databind.json.JsonMapper json) {
        this.rows=rows; this.authorizations=authorizations; this.clients=clients;
        this.legacy=legacy;this.json=json;
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
        for(var token:legacy.grants(uid)) {
            var client=clients.find(token.clientId());
            var scopes=new LinkedHashSet<String>();json.readTree(token.scopes()).valueStream().forEach(scope->scopes.add(scope.asString()));
            result.add(new Grant(token.id(),uid,client.id(),token.name(),scopes,false,instant(token.createdAt()),
                    instant(token.updatedAt()),instant(token.expiresAt()),new Client(client.id(),client.name(),client.redirect())));
        }
        result.sort(Comparator.comparing(Grant::createdAt,Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }
    // Passport 的旧时间字段允许为空，展示时保留“未知”，不编造授权时间。
    private static Instant instant(java.time.LocalDateTime value) { return value==null?null:value.atZone(java.time.ZoneId.systemDefault()).toInstant(); }
    @Transactional
    public void revoke(long uid,String id) {
        var row=rows.lockOwned(id,uid);
        long clientId;
        if(row!=null) { rows.remove(id);clientId=row.clientId(); }
        else {
            var token=legacy.lockOwned(id,uid);
            if(token==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            legacy.revokeToken(id);clientId=token.clientId();
        }
        rows.removeConsent(Long.toString(clientId),Long.toString(uid));
    }
}
