package top.twinklestars.koharuskin.oauth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.stereotype.Service;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.site.SiteOptions;
import top.twinklestars.koharuskin.yggdrasil.YggMapper;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.*;
import java.util.*;

@Service
public class JanusTokens {
    public record Access(String id,AccountUser user,Set<String> scopes,String selected,long issuedAt,long expiresAt) {}
    private final JanusMapper tokens;
    private final AccountMapper accounts;
    private final YggMapper profiles;
    private final OfficialExtensions extensions;
    private final SiteOptions options;
    private final JsonMapper json;
    private final Clock clock;
    private final String publicKeyPath;
    public JanusTokens(JanusMapper tokens,AccountMapper accounts,YggMapper profiles,OfficialExtensions extensions,SiteOptions options,
                       JsonMapper json,Clock clock,@Value("${koharu.oauth-public-key:}") String publicKeyPath) {
        this.tokens=tokens; this.accounts=accounts; this.profiles=profiles; this.extensions=extensions; this.options=options;
        this.json=json; this.clock=clock; this.publicKeyPath=publicKeyPath;
    }
    public Access read(String value) {
        if(!extensions.enabled("yggdrasil-connect")||publicKeyPath.isBlank()) throw invalid();
        try {
            var jwt=SignedJWT.parse(value);
            if(!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())||!jwt.verify(new RSASSAVerifier(publicKey()))) throw invalid();
            var claims=jwt.getJWTClaimsSet(); var now=clock.instant();
            if(claims.getJWTID()==null||claims.getSubject()==null||claims.getExpirationTime()==null||claims.getIssueTime()==null
                    ||!claims.getExpirationTime().toInstant().isAfter(now)||claims.getIssueTime().toInstant().isAfter(now.plusSeconds(30))
                    ||(claims.getNotBeforeTime()!=null&&claims.getNotBeforeTime().toInstant().isAfter(now))) throw invalid();
            if(claims.getIssuer()!=null&&!claims.getIssuer().equals(options.text("ygg_connect_server_url","").replaceAll("/+$",""))) throw invalid();
            var row=tokens.find(claims.getJWTID());
            if(row==null||!claims.getSubject().equals(Long.toString(row.userId()))||!claims.getAudience().equals(List.of(Long.toString(row.clientId())))) throw invalid();
            var account=accounts.findById(row.userId());
            if(account==null||!account.isEnabled()||(options.enabled("require_verification",false)&&!accounts.verified(account.uid()))) throw invalid();
            var scopes=new HashSet<String>(); json.readTree(row.scopes()).valueStream().forEach(scope->scopes.add(scope.asString()));
            var signedScopes=claims.getStringListClaim("scopes");
            if(signedScopes==null) throw invalid();
            scopes.retainAll(signedScopes);
            String selected=claims.getStringClaim("selectedProfile");
            if(scopes.contains(OAuthScopes.SELECT)) {
                var player=selected==null?null:profiles.profile(selected);
                if(player==null||player.uid()!=account.uid()) throw invalid();
                Long renamed=tokens.renamedAt(selected);
                if(renamed!=null&&renamed>claims.getIssueTime().getTime()) throw invalid();
            } else selected=null;
            return new Access(row.id(),account,Set.copyOf(scopes),selected,claims.getIssueTime().getTime(),
                    Math.min(claims.getExpirationTime().toInstant().getEpochSecond(),row.expiresAt().atZone(ZoneId.systemDefault()).toEpochSecond()));
        } catch(ParseException|JOSEException error) { throw invalid(); }
    }
    private RSAPublicKey publicKey() {
        try {
            String pem=Files.readString(Path.of(publicKeyPath));
            return (RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(
                    pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s",""))));
        } catch(IOException|GeneralSecurityException error) { throw new IllegalStateException("无法读取 OAuth 验签公钥",error); }
    }
    private static BadOpaqueTokenException invalid() { return new BadOpaqueTokenException("令牌无效或已失效"); }
}
