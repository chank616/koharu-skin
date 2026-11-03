package top.twinklestars.koharuskin.yggdrasil;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.twinklestars.koharuskin.account.AccountMapper;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.player.Player;
import top.twinklestars.koharuskin.player.PlayerMapper;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class YggAuth {
    private final YggMapper tokens;
    private final AccountMapper accounts;
    private final PlayerMapper players;
    private final YggProfiles profiles;
    private final PasswordEncoder passwords;
    private final SiteOptions options;
    private final Clock clock;
    private final YggOAuth oauth;
    private final YggLogs logs;
    private final SecureRandom random=new SecureRandom();
    private final ConcurrentHashMap<String,Long> attempts=new ConcurrentHashMap<>();

    public YggAuth(YggMapper tokens, AccountMapper accounts, PlayerMapper players, YggProfiles profiles,
                   PasswordEncoder passwords, SiteOptions options, Clock clock,YggOAuth oauth,YggLogs logs) {
        this.tokens=tokens; this.accounts=accounts; this.players=players; this.profiles=profiles;
        this.passwords=passwords; this.options=options; this.clock=clock;
        this.oauth=oauth;
        this.logs=logs;
    }

    @Transactional
    public Map<String,Object> authenticate(String name,String password,String clientToken,boolean requestUser,String ip) {
        var account=credentials(name,password);
        requireUsableAccount(account);
        options.lockRegistration();
        var current=accounts.lockById(account.uid());
        if (!fingerprint(account).equals(fingerprint(current))) throw YggException.invalid();
        requireUsableAccount(current);
        var owned=players.list(account.uid());
        Player selected=owned.size()==1?owned.getFirst():null;
        if (!account.email().equalsIgnoreCase(name)) selected=owned.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        var response=issue(account,clientToken==null?UUID.randomUUID().toString().replace("-",""):clientToken,selected,requestUser,owned);
        logs.record("authenticate",account.uid(),selected==null?0:selected.pid(),ip);
        return response;
    }

    @Transactional
    public Map<String,Object> refresh(String accessToken,String clientToken,String profileId,String profileName,boolean requestUser,String ip) {
        options.lockRegistration();
        var token=tokens.lock(hash(accessToken));
        check(token,clientToken,true);
        var account=accounts.findById(token.userUid());
        Player selected=token.playerPid()==null?null:players.find(token.playerPid());
        if (profileId!=null) {
            if (selected!=null) throw YggException.badRequest("Access token already has a profile assigned.");
            selected=tokens.profile(profileId);
            if (selected==null || selected.uid()!=account.uid() || !selected.name().equals(profileName)) {
                throw YggException.forbidden("The selected profile does not belong to this account.");
            }
        }
        tokens.delete(token.tokenHash());
        var response=issue(account,token.clientToken(),selected,requestUser,players.list(account.uid()));
        logs.record("refresh",account.uid(),selected==null?0:selected.pid(),ip);
        return response;
    }

    public void validate(String accessToken,String clientToken,String ip) {
        var token=tokens.find(hash(accessToken));
        if(token==null) {
            var external=oauth.read(accessToken);
            logs.record("validate",external.userUid(),tokens.profile(external.profileUuid()).pid(),ip);
        } else {
            check(token,clientToken,false);
            logs.record("validate",token.userUid(),token.playerPid()==null?0:token.playerPid(),ip);
        }
    }

    @Transactional
    public void invalidate(String accessToken,String ip) {
        var token=tokens.find(hash(accessToken));
        tokens.delete(hash(accessToken));long externalUid=oauth.invalidate(accessToken);
        logs.record("invalidate",token==null?externalUid:token.userUid(),token==null||token.playerPid()==null?0:token.playerPid(),ip);
    }

    @Transactional
    public void signout(String name,String password,String ip) {
        var account=credentials(name,password);
        options.lockRegistration();
        accounts.lockById(account.uid());
        tokens.revokeUser(account.uid());
        oauth.signout(account.uid());
        logs.record("signout",account.uid(),0,ip);
    }

    @Transactional
    public void join(String accessToken,String profileId,String serverId,String ip) {
        var token=tokens.lock(hash(accessToken));
        Player player;
        if(token==null) {
            var external=oauth.read(accessToken);
            if(!external.profileUuid().equals(profileId)) throw YggException.invalid();
            oauth.remember(external);
            player=tokens.profile(external.profileUuid());
        } else {
            check(token,null,false);
            if (token.playerPid()==null || !players.uuid(players.find(token.playerPid()).name()).replace("-","").equals(profileId)) throw YggException.invalid();
            player=players.find(token.playerPid());
        }
        tokens.deleteExpiredSessions(now());
        tokens.join(new YggMapper.Join(hash(serverId),hash(accessToken),ip,now()+120));
        logs.record("join",player.uid(),player.pid(),ip,"{\"serverHash\":\""+hash(serverId)+"\"}");
    }

    public Map<String,Object> hasJoined(String name,String serverId,String ip,String requestIp) {
        var session=tokens.session(hash(serverId));
        if (session==null || session.expiresAt()<=now() || (ip!=null && !ip.equals(session.clientIp()))) return null;
        var token=tokens.find(session.tokenHash());
        if(token==null) {
            var player=oauth.joined(session.tokenHash());
            if(player==null||!player.name().equalsIgnoreCase(name)) return null;
            var profile=profiles.full(player,false);
            logs.record("has_joined",player.uid(),player.pid(),requestIp,"{\"serverHash\":\""+hash(serverId)+"\"}");
            return profile;
        }
        if (!usable(token,false) || token.playerPid()==null) return null;
        var player=players.find(token.playerPid());
        if (!player.name().equalsIgnoreCase(name)) return null;
        var profile=profiles.full(player,false);
        logs.record("has_joined",player.uid(),player.pid(),requestIp,"{\"serverHash\":\""+hash(serverId)+"\"}");
        return profile;
    }

    public AccountUser bearer(String authorization,String profileId) {
        if (authorization==null || !authorization.regionMatches(true,0,"Bearer ",0,7)) throw new YggException(401,"Unauthorized","Missing access token.");
        var token=tokens.find(hash(authorization.substring(7)));
        if(token==null) {
            YggOAuthMapper.Token external;
            try { external=oauth.read(authorization.substring(7)); }
            catch(YggException error) { throw new YggException(401,"Unauthorized","Invalid token."); }
            if(!external.profileUuid().equals(profileId)) throw YggException.forbidden("The selected profile does not match this token.");
            return accounts.findById(external.userUid());
        }
        if (!usable(token,false)) throw new YggException(401,"Unauthorized","Invalid token.");
        var player=tokens.profile(profileId);
        if (player==null) throw new YggException(404,"Not Found","Profile not found.");
        if (player.uid()!=token.userUid() || token.playerPid()==null || player.pid()!=token.playerPid()) throw YggException.forbidden("The selected profile does not match this token.");
        return accounts.findById(token.userUid());
    }

    private Map<String,Object> issue(AccountUser account,String clientToken,Player selected,boolean requestUser,List<Player> owned) {
        long now=now();
        tokens.deleteExpiredTokens(now);
        var previous=tokens.userTokens(account.uid());
        int limit=Math.max(1,options.number("ygg_tokens_limit",10));
        for (int i=0;i<=previous.size()-limit;i++) tokens.delete(previous.get(i));
        String accessToken=HexFormat.of().formatHex(random.generateSeed(32));
        int refreshLifetime=options.number("ygg_token_expire_2",604800);
        int validLifetime=Math.min(options.number("ygg_token_expire_1",259200),refreshLifetime);
        tokens.insert(new YggMapper.Token(hash(accessToken),account.uid(),clientToken,selected==null?null:selected.pid(),
                selected==null?null:selected.name(),fingerprint(account),clock.instant().toEpochMilli(),now+validLifetime,now+refreshLifetime));
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("accessToken",accessToken); response.put("clientToken",clientToken);
        response.put("availableProfiles",owned.stream().map(profiles::simple).toList());
        if (selected!=null) response.put("selectedProfile",profiles.simple(selected));
        if (requestUser) response.put("user",Map.of("id",userId(account.email()),"properties",List.of()));
        return response;
    }

    private AccountUser credentials(String name,String password) {
        var account=name.contains("@")?accounts.findByEmail(name):accounts.findByPlayerName(name);
        String key=account==null?"name:"+name.toLowerCase(Locale.ROOT):"uid:"+account.uid();
        long now=clock.instant().toEpochMilli();
        attempts.entrySet().removeIf(entry -> entry.getValue()<=now);
        attempts.compute(key,(ignored,until) -> {
            if (until!=null && until>now) throw YggException.forbidden("Too many requests. Please try again later.");
            return now+options.number("ygg_rate_limit",1000);
        });
        if (account==null || !passwords.matches(password,account.password())) throw YggException.forbidden("Invalid credentials. Invalid username or password.");
        return account;
    }

    private void requireUsableAccount(AccountUser account) {
        if (!account.isEnabled()) throw YggException.forbidden("This account is banned.");
        if (options.enabled("require_verification",false) && !accounts.verified(account.uid())) throw YggException.forbidden("Please verify your email first.");
    }

    private void check(YggMapper.Token token,String clientToken,boolean refreshable) {
        if (!usable(token,refreshable) || (clientToken!=null && !token.clientToken().equals(clientToken))) throw YggException.invalid();
    }

    private boolean usable(YggMapper.Token token,boolean refreshable) {
        if (token==null || token.expiresAt()<=now()) return false;
        var account=accounts.findById(token.userUid());
        if (account==null || !account.isEnabled() || !token.credentialsHash().equals(fingerprint(account))
                || (options.enabled("require_verification",false) && !accounts.verified(account.uid()))) return false;
        if (!refreshable && token.validUntil()<=now()) return false;
        if (token.playerPid()!=null) {
            var player=players.find(token.playerPid());
            if (player==null || player.uid()!=account.uid()) return false;
            if (!refreshable && !player.name().equals(token.profileName())) return false;
        }
        return true;
    }

    private long now() { return clock.instant().getEpochSecond(); }
    private static String fingerprint(AccountUser user) { return hash(user.email()+"\n"+user.password()); }
    static String hash(String value) { return HexFormat.of().formatHex(digest("SHA-256",value.getBytes(StandardCharsets.UTF_8))); }
    private static byte[] digest(String algorithm,byte[] data) {
        try { return MessageDigest.getInstance(algorithm).digest(data); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static String userId(String email) {
        byte[] name=email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] namespace=HexFormat.of().parseHex("6ba7b8109dad11d180b400c04fd430c8");
        byte[] digest=digest("SHA-1",ByteBuffer.allocate(16+name.length).put(namespace).put(name).array());
        digest[6]=(byte)((digest[6]&0x0f)|0x50); digest[8]=(byte)((digest[8]&0x3f)|0x80);
        return HexFormat.of().formatHex(digest,0,16);
    }
}
