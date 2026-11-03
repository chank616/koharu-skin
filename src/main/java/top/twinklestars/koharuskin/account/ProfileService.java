package top.twinklestars.koharuskin.account;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.site.SiteOptions;

@Service
public class ProfileService {
    private final AccountMapper accounts;
    private final ProfileMapper profiles;
    private final PasswordEncoder passwords;
    private final OfficialExtensions extensions;
    private final SiteOptions options;
    private final top.twinklestars.koharuskin.player.PlayerService players;

    public ProfileService(AccountMapper accounts, ProfileMapper profiles, PasswordEncoder passwords,
                          OfficialExtensions extensions, SiteOptions options,
                          top.twinklestars.koharuskin.player.PlayerService players) {
        this.accounts=accounts; this.profiles=profiles; this.passwords=passwords;
        this.extensions=extensions; this.options=options;
        this.players=players;
    }

    @Transactional
    public void nickname(long uid, String nickname) {
        options.lockRegistration();
        if (extensions.enabled("single-player-limit")) throw new BusinessException("启用单角色限制时，请通过修改角色名更改昵称");
        profiles.nickname(uid, nickname);
    }

    @Transactional
    public void password(long uid, String current, String replacement) {
        checkPassword(accounts.lockById(uid), current);
        accounts.password(uid, passwords.encode(replacement));
    }

    @Transactional
    public void email(long uid, String email, String password) {
        options.lockRegistration();
        if(extensions.enabled("auth-henan")) throw new BusinessException("本站启用了河南统一认证，不能自行更改邮箱");
        checkPassword(accounts.lockById(uid), password);
        extensions.checkEmail(email);
        if (accounts.findByEmail(email) != null) throw new BusinessException("此邮箱已被使用");
        profiles.email(uid, email);
    }

    @Transactional
    public void bindEmail(long uid, String email) {
        options.lockRegistration();
        if (!accounts.lockById(uid).email().isEmpty()) throw new BusinessException("账号已绑定邮箱，请在个人资料中更改");
        if (accounts.findByEmail(email) != null) throw new BusinessException("此邮箱已被使用");
        accounts.bindEmail(uid, email);
    }

    @Transactional
    public void delete(long uid, String password) {
        options.lockRegistration();
        var user = accounts.lockById(uid);
        if (user.permission() >= 1) throw new BusinessException("拥有管理员权限的账号不能被删除");
        checkPassword(user, password);
        // 与原站一致：注销删除角色，保留已上传的材质和角色 UUID。
        players.deleteAll(uid);
        profiles.delete(uid);
    }

    private void checkPassword(AccountUser user, String password) {
        if (!passwords.matches(password, user.password())) throw new BusinessException("当前密码不正确");
    }
}
