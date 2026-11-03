package top.twinklestars.koharuskin.player;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.account.BusinessException;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class PlayerService {
    private final PlayerMapper players;
    private final PlayerNames names;
    private final SiteOptions options;
    private final OfficialExtensions extensions;
    private final top.twinklestars.koharuskin.yggdrasil.UnionPlayerSync union;
    private final top.twinklestars.koharuskin.oauth.JanusMapper janus;

    public PlayerService(PlayerMapper players, PlayerNames names, SiteOptions options, OfficialExtensions extensions,
                         top.twinklestars.koharuskin.yggdrasil.UnionPlayerSync union,top.twinklestars.koharuskin.oauth.JanusMapper janus) {
        this.players = players;
        this.names = names;
        this.options = options;
        this.extensions = extensions;
        this.union = union;
        this.janus = janus;
    }

    public void requireVerified(long uid) {
        if (options.enabled("require_verification", false) && !players.verified(uid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请先验证邮箱");
        }
    }

    public Player owned(long uid, long pid) {
        Player player = players.find(pid);
        if (player == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
        if (player.uid() != uid) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此角色");
        return player;
    }

    @Transactional
    public Player add(long uid, String name, boolean bind) {
        options.lockRegistration();
        names.validate(name);
        boolean single = extensions.enabled("single-player-limit");
        if (bind && (!single || players.list(uid).size() == 1)) throw new BusinessException("当前账号无需绑定角色");
        if (!bind && single) throw new BusinessException("本站启用了单角色限制，不能增加角色");
        var existing = players.findByName(name);
        if (bind && existing != null && existing.uid() == uid) {
            if (players.countName(name) > 1) throw new BusinessException("旧角色名存在重复，请先由管理员处理");
            deleteOthers(uid, existing.pid());
            players.nickname(uid, existing.name());
            uuid(existing.name());
            return existing;
        }
        if (players.countName(name) > 0) throw new BusinessException("此角色名已被使用");
        int score = players.lockScore(uid);
        int cost = bind ? 0 : options.number("score_per_player", 100);
        if (score < cost) throw new BusinessException("积分不足，无法添加角色");
        players.create(uid, name);
        players.changeScore(uid, -cost);
        if (bind) {
            players.nickname(uid, name);
            deleteOthers(uid, players.findByName(name).pid());
        }
        uuid(name);
        syncUnion("POST",name);
        return players.findByName(name);
    }

    @Transactional
    public Player rename(long uid, long pid, String name) {
        options.lockRegistration();
        Player player = owned(uid, pid);
        names.validate(name);
        if (player.name().equals(name)) return player;
        if (players.countName(player.name()) > 1) throw new BusinessException("旧角色名存在重复，请先由管理员处理");
        Player occupied = players.findByName(name);
        if (occupied != null && occupied.pid() != pid) throw new BusinessException("此角色名已被使用");
        uuid(player.name());
        if (!player.name().equalsIgnoreCase(name)) players.deleteUuid(name);
        players.renameUuid(player.name(), name);
        players.rename(pid, name);
        janus.renamed(uuid(name),System.currentTimeMillis());
        syncUnion("PUT",name);
        if (extensions.enabled("single-player-limit")) players.nickname(uid, name);
        return players.find(pid);
    }

    @Transactional
    public void delete(long uid, long pid) {
        options.lockRegistration();
        var player=owned(uid, pid);
        if (extensions.enabled("single-player-limit")) throw new BusinessException("本站启用了单角色限制，不能删除角色");
        players.lockScore(uid);
        players.delete(pid);
        syncUnion("DELETE",player.name());
        if (options.enabled("return_score", true)) players.changeScore(uid, options.number("score_per_player", 100));
    }

    @Transactional
    public Player setTextures(long uid, long pid, Integer skin, Integer cape) {
        options.lockRegistration();
        Player player = owned(uid, pid);
        if (skin != null && skin > 0) checkTexture(uid, skin, false);
        if (cape != null && cape > 0) checkTexture(uid, cape, true);
        players.textures(pid, skin == null ? player.tidSkin() : skin, cape == null ? player.tidCape() : cape);
        syncUnion("PUT",player.name());
        return players.find(pid);
    }

    private void deleteOthers(long uid,long keepPid) {
        for(var player:players.list(uid)) if(player.pid()!=keepPid) syncUnion("DELETE",player.name());
        players.deleteOtherPlayers(uid,keepPid);
    }

    @Transactional
    public void deleteAll(long uid) {
        options.lockRegistration();
        for(var player:players.list(uid)) {
            syncUnion("DELETE",player.name());
            players.delete(player.pid());
        }
    }

    @Transactional
    public void syncUnion(String method,String name) {
        if(union.enabled()) union.afterCommit(method,uuid(name),name);
    }

    private void checkTexture(long uid, int tid, boolean cape) {
        String type = players.textureType(tid);
        if (type == null) throw new BusinessException("材质不存在");
        if (cape != type.equals("cape")) throw new BusinessException("皮肤和披风的材质类型不匹配");
        if (players.inCloset(uid, tid) == 0) throw new BusinessException("请先将材质加入衣柜");
    }

    @Transactional
    public String uuid(String name) {
        options.lockRegistration();
        String existing = players.uuid(name);
        if (existing != null) return existing;
        UUID uuid = options.text("ygg_uuid_algorithm", "v4").equals("v3")
                ? UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)) : UUID.randomUUID();
        String value = uuid.toString().replace("-", "");
        players.insertUuid(name, value);
        return value;
    }
}
