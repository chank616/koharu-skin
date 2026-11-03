package top.twinklestars.koharuskin.yggdrasil;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.player.*;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Service
public class UnionProfiles {
    private final UnionMember union;
    private final UnionMapper mapper;
    private final PlayerMapper players;
    private final PlayerService playerService;
    private final SiteOptions options;
    private final JsonMapper json;
    public UnionProfiles(UnionMember union,UnionMapper mapper,PlayerMapper players,PlayerService playerService,SiteOptions options,JsonMapper json) {
        this.union=union; this.mapper=mapper; this.players=players; this.playerService=playerService; this.options=options; this.json=json;
    }
    public record Remote(String internalId,String uuid,String mappedUuid,String name,String backendCode,String backend,int bindStatus) {}
    public record Profile(String name,String uuid,Remote self,List<Remote> bound,List<Remote> duplicates) {}
    public record Backend(String code,String root) {}
    public List<Profile> profiles(long uid) {
        union.requireEnabled();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=players.list(uid).stream().map(player->CompletableFuture.supplyAsync(()->profile(player),executor)).toList();
            return tasks.stream().map(CompletableFuture::join).toList();
        } catch(CompletionException error) {
            if(error.getCause() instanceof ResponseStatusException response) throw response;
            throw error;
        }
    }
    private Profile profile(Player player) {
        String uuid=normalize(playerService.uuid(player.name()));
        var self=detail(uuid);
        var bound=self.path("bind").valueStream().map(this::remote).toList();
        var excluded=new HashSet<String>(); excluded.add(self.path("internal_id").asString()); bound.forEach(item->excluded.add(item.internalId()));
        var duplicates=union.request("GET","/profile/unmapped/byname/"+segment(player.name()),null).valueStream()
                .map(this::remote).filter(item->!excluded.contains(item.internalId())).toList();
        return new Profile(player.name(),uuid,remote(self),bound,duplicates);
    }
    public List<Backend> backends() {
        return json.readTree(options.text("union_server_list","{}")).valueStream()
                .map(item->new Backend(item.path("code").asString(),safeUrl(item.path("bs_root").asString()))).toList();
    }
    private Remote remote(JsonNode item) {
        return new Remote(item.path("internal_id").asString(),item.path("uuid").asString(),item.path("mapped_uuid").asString(),
                item.path("bind_mapped_name").asString(),item.path("backend_scopes").path("self").asString(),safeUrl(item.path("backend").asString()),item.path("bind_status").asInt());
    }
    private JsonNode detail(String uuid) {
        var detail=union.request("GET","/profile/detail/"+uuid,null);
        if(!detail.path("uuid").isString()||!normalize(detail.get("uuid").asString()).equals(uuid))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"联盟中的角色信息与本站不一致，请联系管理员同步角色");
        return detail;
    }
    public Map<String,String> bind(long uid,String input) {
        String uuid=owned(uid,input);
        var result=union.request("POST","/profile/bind",Map.of("uuid",uuid));
        if(!result.path("token").isString()||result.get("token").asString().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"联盟未返回绑定码");
        return Map.of("token",result.get("token").asString());
    }
    public void bindTo(long uid,String input,String token) { union.request("POST","/profile/bindto",Map.of("uuid",owned(uid,input),"token",token)); }
    public void unbind(long uid,String input) { union.request("POST","/profile/unbind",Map.of("uuid",owned(uid,input))); }
    public void remap(long uid,String input,String target) {
        String uuid=owned(uid,input),destination=normalize(target);
        var self=detail(uuid);
        var allowed=new HashSet<String>(); allowed.add(normalize(self.path("mapped_uuid").asString()));
        self.path("bind").valueStream().forEach(item->allowed.add(normalize(item.path("mapped_uuid").asString())));
        if(!allowed.contains(destination)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"只能使用已关联角色的映射 UUID");
        union.request("POST","/profile/remapuuid",Map.of("me",uuid,"target",destination));
    }
    public JsonNode securityLevel() {
        var code=union.request("POST","/code",Map.of("token",options.text("union_member_key",""))).path("code").asString("");
        if(code.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"联盟未返回本站编号");
        return union.request("GET","/backend/"+segment(code)+"/security/level",null);
    }
    public Map<String,String> description() {
        String description=union.request("GET","",null).path("meta").path("description").asString("");
        String html=org.commonmark.renderer.html.HtmlRenderer.builder().sanitizeUrls(true).build()
                .render(org.commonmark.parser.Parser.builder().build().parse(description));
        return Map.of("html",org.jsoup.Jsoup.clean(html,org.jsoup.safety.Safelist.relaxed()));
    }
    private String owned(long uid,String input) {
        union.requireEnabled(); String uuid=normalize(input);
        if(mapper.owned(uid,uuid)==0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只能操作自己的角色");
        return uuid;
    }
    private String normalize(String value) {
        String uuid=value.replace("-","").toLowerCase(Locale.ROOT);
        if(!uuid.matches("[a-f0-9]{32}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UUID 格式无效");
        return uuid;
    }
    private String safeUrl(String value) { return value.startsWith("https://")||value.startsWith("http://")?value:""; }
    private String segment(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
}
