package top.twinklestars.koharuskin.account;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.player.PlayerNames;
import top.twinklestars.koharuskin.site.SiteOptions;

@Service
public class RegistrationService {
    private final RegistrationMapper registrations;
    private final AccountMapper accounts;
    private final SiteOptions options;
    private final PlayerNames names;
    private final OfficialExtensions extensions;
    private final PasswordEncoder passwords;

    public RegistrationService(RegistrationMapper registrations, AccountMapper accounts, SiteOptions options,
                               PlayerNames names, OfficialExtensions extensions, PasswordEncoder passwords) {
        this.registrations = registrations;
        this.accounts = accounts;
        this.options = options;
        this.names = names;
        this.extensions = extensions;
        this.passwords = passwords;
    }

    @Transactional
    public AccountUser register(String email, String password, String nickname, String playerName, String ip) {
        options.lockRegistration();
        extensions.checkEmail(email);
        if (accounts.findByEmail(email) != null) throw new BusinessException("此邮箱已注册");
        boolean withPlayer = options.enabled("register_with_player_name", true);
        String name = withPlayer ? playerName : nickname;
        if (withPlayer) {
            names.validate(name);
            if (registrations.countName(name) > 0) throw new BusinessException("此角色名已被使用");
        } else if (name == null || name.isBlank() || name.length() > 50) {
            throw new BusinessException("昵称不能为空且不能超过 50 个字符");
        }
        if (registrations.countIp(ip) >= options.number("regs_per_ip", 3)) throw new BusinessException("此 IP 的注册数量已达上限");
        registrations.create(email, name, passwords.encode(password), options.number("user_initial_score", 1000), ip,
                java.time.LocalDateTime.now().minusDays(1));
        AccountUser user = accounts.findByEmail(email);
        if (withPlayer) registrations.createPlayer(user.uid(), name);
        return user;
    }
}
