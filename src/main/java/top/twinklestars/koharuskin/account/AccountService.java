package top.twinklestars.koharuskin.account;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountService implements UserDetailsService {
    private final AccountMapper accounts;

    public AccountService(AccountMapper accounts) { this.accounts = accounts; }

    @Override
    public UserDetails loadUserByUsername(String identification) {
        var user = identification.contains("@")
                ? accounts.findByEmail(identification.trim())
                : accounts.findByPlayerName(identification.trim());
        if (user == null) throw new UsernameNotFoundException("账号不存在");
        return user;
    }
}
