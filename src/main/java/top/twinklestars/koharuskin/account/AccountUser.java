package top.twinklestars.koharuskin.account;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public record AccountUser(long uid, String email, String nickname, String password, int permission)
        implements UserDetails {
    @Override
    public String getUsername() { return email; }

    @Override
    public String getPassword() { return password; }

    @Override
    public boolean isEnabled() { return permission >= 0; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(permission >= 1 ? "ROLE_ADMIN" : "ROLE_USER"));
    }
}
