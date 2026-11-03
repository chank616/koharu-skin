package top.twinklestars.koharuskin.yggdrasil;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;

@Service
public class UnionMember {
    private final SiteOptions options;
    private final OfficialExtensions extensions;
    private final UnionMapper mapper;
    private final YggMapper tokens;
    private final YggKeys keys;
    private final JsonMapper json;
    private final Clock clock;
    private final boolean networkEnabled;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public UnionMember(SiteOptions options,OfficialExtensions extensions,UnionMapper mapper,YggMapper tokens,YggKeys keys,
                       JsonMapper json,Clock clock,@Value("${koharu.union-network-enabled:true}") boolean networkEnabled) {
        this.options=options; this.extensions=extensions; this.mapper=mapper; this.tokens=tokens; this.keys=keys;
        this.json=json; this.clock=clock; this.networkEnabled=networkEnabled;
    }
    public void requireEnabled() {
        if(!extensions.enabled("yggdrasil-connect")) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"联合认证扩展未启用");
    }
    public JsonNode request(String method,String path,Object data) {
        requireEnabled();
        if(!networkEnabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"当前环境已关闭联盟网络连接");
        String root=options.text("union_api_root","").replaceAll("/+$","");
        if(root.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"尚未配置联盟地址");
        var uri=URI.create(root+path);
        if(!("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"联盟地址无效");
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Accept","application/json");
        if(!path.isEmpty()) builder.header("X-Union-Member-Key",options.text("union_member_key",""));
        builder.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(data)));
        if(data!=null) builder.header("Content-Type","application/json");
        try {
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"联盟服务请求失败");
            return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
        } catch(IOException|tools.jackson.core.JacksonException error) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"联盟服务不可用"); }
        catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"联盟请求已中断"); }
    }
    public void verify(HttpServletRequest request,byte[] body) {
        requireEnabled();
        String stamp=request.getHeader("X-Message-Timestamp"),nonce=request.getHeader("X-Message-Nonce"),signature=request.getHeader("X-Message-Signature");
        if(stamp==null||!stamp.matches("[0-9]{1,11}")||nonce==null||!nonce.matches("[A-Za-z0-9_-]{1,128}")||signature==null) throw forbidden();
        long timestamp=Long.parseLong(stamp),now=clock.instant().getEpochSecond();
        if(timestamp<now-10||timestamp>now+30) throw forbidden();
        String pem=request("GET","",null).path("union_host_signature_public_key").asString("");
        try {
            byte[] bytes=Base64.getDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s",""));
            var key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
            var verifier=Signature.getInstance("SHA256withRSA"); verifier.initVerify(key);
            verifier.update(body); verifier.update((stamp+nonce).getBytes(StandardCharsets.UTF_8));
            if(!verifier.verify(Base64.getDecoder().decode(signature))) throw forbidden();
        } catch(GeneralSecurityException|IllegalArgumentException error) { throw forbidden(); }
        mapper.expireNonces(now);
        if(mapper.claimNonce(nonce,now+60)!=1) throw forbidden();
    }
    public Map<String,Object> hello() {
        requireEnabled();
        return Map.of("yggdrasilApiVersion","0.0.1","serverListVersion",options.text("union_server_list_version","0"),
                "privateKeyVersion",options.text("union_private_key_version","0"),
                "enabledFeatures",options.enabled("require_verification",false)?List.of("emailVerification"):List.of());
    }
    @Transactional public void updateList() {
        var response=request("GET","/serverlist",null);
        if(!(response.path("servers").isObject()||response.path("servers").isArray())||!response.has("version")) throw badResponse();
        options.lockRegistration();
        save("union_server_list",json.writeValueAsString(response.get("servers")));
        save("union_server_list_version",response.get("version").asString());
    }
    @Transactional public void updatePrivateKey() {
        var response=request("GET","/privatekey",null);
        if(!response.path("privateKey").isString()||!response.has("privateKeyVersion")) throw badResponse();
        options.lockRegistration();
        save("ygg_private_key",response.get("privateKey").asString());
        try { keys.pair(); }
        catch(IllegalStateException|IllegalArgumentException error) { throw badResponse(); }
        save("union_private_key_version",response.get("privateKeyVersion").asString());
    }
    @Transactional public void updateMemberKey(String key) {
        if(key.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"成员密钥不能为空");
        options.lockRegistration(); save("union_member_key",key);
    }
    @Transactional public void sync() {
        options.lockRegistration();
        var profiles=new LinkedHashMap<String,String>();
        for(var row:mapper.mappings()) if(row.uid()!=null) profiles.put(row.uuid(),row.name());
        request("POST","/sync",Map.of("profileList",profiles));
    }
    @Transactional public void remap(JsonNode input) {
        if(!input.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UUID 映射格式无效");
        var changes=new LinkedHashMap<String,String>();
        input.properties().forEach(entry->changes.put(uuid(entry.getKey()),uuid(entry.getValue().asString())));
        options.lockRegistration();
        var rows=mapper.mappings();
        var destinations=new HashMap<String,String>();
        for(var row:rows) {
            String current=uuid(row.uuid()),next=changes.getOrDefault(current,current);
            String previous=destinations.putIfAbsent(next,current);
            if(previous!=null&&!previous.equals(current)) throw new ResponseStatusException(HttpStatus.CONFLICT,"映射后的 UUID 已被其他角色使用");
        }
        // 根据原始快照更新，避免 A→B、B→C 的批量映射把 A 再次映射为 C。
        for(var row:rows) if(changes.containsKey(uuid(row.uuid()))) {
            mapper.remap(row.name(),changes.get(uuid(row.uuid())));
            if(row.uid()!=null) tokens.revokeUser(row.uid());
        }
    }
    public String email(String name) { return mapper.email(name); }
    public Map<String,Object> diagnose(JsonNode input) { return Map.of("nonce",input.path("nonce").asString(""),"timestamp",clock.instant().toEpochMilli()/1000.0); }
    private String uuid(String value) {
        String normalized=value.replace("-","").toLowerCase(Locale.ROOT);
        if(!normalized.matches("[a-f0-9]{32}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UUID 格式无效");
        return normalized;
    }
    private void save(String name,String value) { if(options.get(name)==null) options.insert(name,value); else options.update(name,value); }
    public boolean networkEnabled() { return networkEnabled; }
    private ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"Union host verification failure."); }
    private ResponseStatusException badResponse() { return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"联盟响应格式无效"); }
}
