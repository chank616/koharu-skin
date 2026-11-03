package top.twinklestars.koharuskin.extension;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import top.twinklestars.koharuskin.player.*;
import top.twinklestars.koharuskin.texture.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.io.*;
import javax.imageio.ImageIO;

@RestController
public class SkinApiController {
    private final OfficialExtensions extensions;
    private final SkinApiMapper mapper;
    private final PlayerMapper players;
    private final PlayerService playerService;
    private final TextureMapper textures;
    private final Path directory;
    private final List<byte[]> tokens;
    private final top.twinklestars.koharuskin.site.SiteOptions options;

    public SkinApiController(OfficialExtensions extensions,SkinApiMapper mapper,PlayerMapper players,PlayerService playerService,
                             TextureMapper textures,@Value("${koharu.textures:storage/textures}") String directory,
                             @Value("${koharu.skin-api-tokens:}") String tokens,top.twinklestars.koharuskin.site.SiteOptions options) {
        this.extensions=extensions; this.mapper=mapper; this.players=players; this.playerService=playerService;
        this.textures=textures; this.directory=Path.of(directory);
        this.tokens=tokenBytes(tokens); this.options=options;
    }

    private void authorize(HttpServletRequest request,Map<String,?> input) {
        if(!extensions.enabled("skin-api-extension")) throw new ApiError(404,"扩展未启用");
        String token=value(input,"token",request.getParameter("token"));
        if(token==null||token.isEmpty()) {
            String header=request.getHeader("Authorization");
            token=header!=null&&header.regionMatches(true,0,"Bearer ",0,7)?header.substring(7):"";
        }
        byte[] candidate=token.getBytes(StandardCharsets.UTF_8);
        String configured=options.get("skin_api_tokens");
        var validTokens=configured==null?tokens:tokenBytes(configured);
        if(validTokens.stream().noneMatch(valid->MessageDigest.isEqual(valid,candidate))) throw new ApiError(401,"无效的token");
    }

    private List<byte[]> tokenBytes(String tokens) {
        return Arrays.stream(tokens.split(",")).map(String::trim).filter(token->!token.isEmpty())
                .map(token->token.getBytes(StandardCharsets.UTF_8)).toList();
    }

    @PostMapping(value="/ext/get-uids-by-suffix",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public Map<String,Object> suffixJson(@RequestBody Map<String,Object> input,HttpServletRequest request) { return suffix(input,request); }
    @PostMapping("/ext/get-uids-by-suffix") @Transactional
    public Map<String,Object> suffixForm(@RequestParam Map<String,String> input,HttpServletRequest request) { return suffix(input,request); }

    private Map<String,Object> suffix(Map<String,?> input,HttpServletRequest request) {
        authorize(request,input);
        String suffix=value(input,"suffix","").trim();
        if(suffix.isEmpty()) throw new ApiError(400,"邮箱后缀不能为空");
        if(!suffix.matches("@[a-zA-Z0-9.-]+")) throw new ApiError(400,"邮箱后缀格式不正确，例如：@example.edu.cn");
        var accounts=new LinkedHashMap<Long,Map<String,Object>>();
        var roles=new LinkedHashMap<Long,List<Map<String,String>>>();
        for(var row:mapper.bySuffix(suffix.toLowerCase(Locale.ROOT))) {
            if(!accounts.containsKey(row.uid())) {
                var owned=new ArrayList<Map<String,String>>(); roles.put(row.uid(),owned);
                accounts.put(row.uid(),Map.of("account_name",row.nickname(),"roles",owned));
            }
            if(row.pid()!=null) roles.get(row.uid()).add(Map.of("game_id",row.name(),"uuid",playerService.uuid(row.name())));
        }
        return Map.of("suffix",suffix,"accounts",accounts.values());
    }

    @GetMapping("/ext/get-avatar-by-game-id")
    public ResponseEntity<byte[]> gameAvatar(@RequestParam Map<String,String> input,HttpServletRequest request,WebRequest web) throws IOException,java.security.NoSuchAlgorithmException {
        authorize(request,input);
        String name=value(input,"game_id","").trim();
        if(name.isEmpty()) throw new ApiError(400,"游戏 ID 不能为空");
        var player=players.findByName(name);
        if(player==null) throw new ApiError(404,"角色不存在");
        String requested=value(input,"size","100");
        int size=requested.matches("[+-]?\\d{1,9}")?Math.max(16,Math.min(512,Integer.parseInt(requested))):16;
        Texture texture=available(player.tidSkin());
        String version=texture==null?"default":texture.hash();
        String etag="\""+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((version+":"+size).getBytes(StandardCharsets.UTF_8)))+"\"";
        // 头像缓存仅用于持有令牌的调用方，每次请求仍先鉴权。
        boolean notModified=web.checkNotModified(etag);
        var response=ResponseEntity.status(notModified?HttpStatus.NOT_MODIFIED:HttpStatus.OK)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePrivate()).eTag(etag);
        return notModified?response.build():response.contentType(MediaType.IMAGE_PNG).body(render(texture,size));
    }

    @PostMapping(value="/ext/get-avatar-by-uid",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> userAvatarJson(@RequestBody Map<String,Object> input,HttpServletRequest request) throws IOException { return userAvatar(input,request); }
    @PostMapping("/ext/get-avatar-by-uid")
    public ResponseEntity<byte[]> userAvatarForm(@RequestParam Map<String,String> input,HttpServletRequest request) throws IOException { return userAvatar(input,request); }

    private ResponseEntity<byte[]> userAvatar(Map<String,?> input,HttpServletRequest request) throws IOException {
        authorize(request,input);
        String value=value(input,"uid","");
        if(!value.matches("[+-]?\\d{1,18}")||Long.parseLong(value)==0) throw new ApiError(400,"用户ID必须是数字");
        Integer tid=textures.avatarId(Long.parseLong(value));
        if(tid==null) throw new ApiError(404,"用户不存在");
        Texture texture=available(tid);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(render(texture,0));
    }

    private Texture available(int tid) {
        var texture=textures.find(tid);
        return texture!=null&&Files.isRegularFile(directory.resolve(texture.hash()))?texture:null;
    }
    private byte[] render(Texture texture,int size) throws IOException {
        java.awt.image.BufferedImage image;
        if(texture==null) {
            if(size==0) size=128;
            try(var input=new ClassPathResource("static/defaults/avatar2d.png").getInputStream()) { image=ImageIO.read(input); }
            var resized=new java.awt.image.BufferedImage(size,size,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var graphics=resized.createGraphics(); graphics.drawImage(image,0,0,size,size,null); graphics.dispose(); image=resized;
        } else {
            var skin=ImageIO.read(directory.resolve(texture.hash()).toFile());
            if(size==0) size=225*(Math.min(skin.getWidth(),256)/64);
            image=SkinImages.avatar(skin,size,false);
        }
        var bytes=new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes); return bytes.toByteArray();
    }
    private String value(Map<String,?> input,String key,String fallback) { return input.get(key)==null?fallback:input.get(key).toString(); }
    @ExceptionHandler(ApiError.class)
    public ResponseEntity<Map<String,String>> error(ApiError error) { return ResponseEntity.status(error.status).body(Map.of("error",error.getMessage())); }
    private static class ApiError extends RuntimeException {
        final int status;
        ApiError(int status,String message) { super(message); this.status=status; }
    }
}
