package top.twinklestars.koharuskin.oauth;

import org.springframework.stereotype.Repository;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import java.time.Duration;
import java.util.Arrays;

@Repository
public class OAuthRegisteredClients implements RegisteredClientRepository {
    private final OAuthClientMapper clients;
    private final OAuthScopes scopes;
    private final top.twinklestars.koharuskin.extension.OfficialExtensions extensions;
    private final top.twinklestars.koharuskin.site.SiteUrls urls;
    public OAuthRegisteredClients(OAuthClientMapper clients,OAuthScopes scopes,top.twinklestars.koharuskin.extension.OfficialExtensions extensions,
                                  top.twinklestars.koharuskin.site.SiteUrls urls) {
        this.clients=clients; this.scopes=scopes; this.extensions=extensions; this.urls=urls;
    }
    @Override public void save(RegisteredClient client) { throw new UnsupportedOperationException("请通过应用管理注册客户端"); }
    @Override public RegisteredClient findById(String id) { return findByClientId(id); }
    @Override public RegisteredClient findByClientId(String id) {
        if(!id.matches("[1-9][0-9]{0,17}")) return null;
        var client=clients.find(Long.parseLong(id));
        if(client==null||client.revoked()) return null;
        var builder=RegisteredClient.withId(id).clientId(id).clientName(client.name())
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(true).requireProofKey(client.secret()==null).build())
                .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .authorizationCodeTimeToLive(Duration.ofMinutes(10)).accessTokenTimeToLive(Duration.ofHours(1))
                        .refreshTokenTimeToLive(Duration.ofDays(30)).reuseRefreshTokens(false).build());
        if(client.secret()==null) builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        else builder.clientSecret(client.secret().startsWith("$2")?"{bcrypt}"+client.secret():"{noop}"+client.secret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
        if(!client.personalAccessClient()&&!client.passwordClient()) builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        Arrays.stream(client.redirect().split(",")).forEach(builder::redirectUri);
        if(extensions.enabled("yggdrasil-connect")) builder.redirectUri(urls.publicUrl()+"/yggc/callback");
        scopes.list().forEach(scope->builder.scope(scope.id()));
        return builder.build();
    }
}
