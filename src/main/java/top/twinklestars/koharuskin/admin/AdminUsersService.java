package top.twinklestars.koharuskin.admin;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.site.SiteOptions;

@Service
@Transactional
public class AdminUsersService {
    private final AccountMapper accounts;
    private final ProfileMapper profiles;
    private final AdminUsersMapper users;
    private final SiteOptions options;
    private final PasswordEncoder passwords;
    private final top.twinklestars.koharuskin.player.PlayerService players;

    public AdminUsersService(AccountMapper accounts, ProfileMapper profiles, AdminUsersMapper users,
                             SiteOptions options, PasswordEncoder passwords,
                             top.twinklestars.koharuskin.player.PlayerService players) {
        this.accounts=accounts; this.profiles=profiles; this.users=users;
        this.options=options; this.passwords=passwords;
        this.players=players;
    }

    private AccountUser authorize(long actorUid, long targetUid) {
        options.lockRegistration();
        var actor=accounts.lockById(actorUid);
        var target=accounts.lockById(targetUid);
        if (target==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (actor==null || actor.permission()<1 || (actorUid!=targetUid && target.permission()>=actor.permission()))
            throw new AccessDeniedException("没有权限修改此用户");
        return actor;
    }

    public void email(long actor, long uid, String email) {
        authorize(actor,uid);
        if (users.emailUsed(uid,email)>0) throw new BusinessException("此邮箱已被使用");
        // 管理员代改邮箱保留原验证状态，与原后台保持一致。
        users.email(uid,email);
    }
    public void verification(long actor, long uid) {
        authorize(actor,uid);
        users.verification(uid);
    }
    public void nickname(long actor, long uid, String nickname) {
        authorize(actor,uid);
        profiles.nickname(uid,nickname);
    }
    public void password(long actor, long uid, String password) {
        authorize(actor,uid);
        accounts.password(uid,passwords.encode(password));
    }
    public void score(long actor, long uid, int score) {
        authorize(actor,uid);
        users.score(uid,score);
    }
    public void permission(long actorUid, long uid, int permission) {
        var actor=authorize(actorUid,uid);
        if (actorUid==uid || (permission==1 && actor.permission()<2))
            throw new AccessDeniedException("没有权限修改此用户的权限");
        users.permission(uid,permission);
    }
    public void delete(long actor, long uid) {
        authorize(actor,uid);
        players.deleteAll(uid);
        profiles.delete(uid);
    }
}
