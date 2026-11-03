package top.twinklestars.koharuskin.oauth;

import org.springframework.stereotype.Service;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.*;
import java.util.Arrays;
import java.util.stream.Collectors;

@Service
public class OAuthConsents implements OAuth2AuthorizationConsentService {
    private final OAuthAuthorizationMapper rows;
    public OAuthConsents(OAuthAuthorizationMapper rows) { this.rows=rows; }
    @Override public void save(OAuth2AuthorizationConsent consent) {
        rows.saveConsent(consent.getRegisteredClientId(),consent.getPrincipalName(),consent.getAuthorities().stream()
                .map(a->a.getAuthority()).collect(Collectors.joining(",")));
    }
    @Override public void remove(OAuth2AuthorizationConsent consent) { rows.removeConsent(consent.getRegisteredClientId(),consent.getPrincipalName()); }
    @Override public OAuth2AuthorizationConsent findById(String clientId,String principal) {
        String saved=rows.findConsent(clientId,principal); if(saved==null) return null;
        var consent=OAuth2AuthorizationConsent.withId(clientId,principal);
        Arrays.stream(saved.split(",")).forEach(value->consent.authority(new SimpleGrantedAuthority(value)));
        return consent.build();
    }
}
