package top.twinklestars.koharuskin.texture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import top.twinklestars.koharuskin.account.AccountUser;

@RestController
public class TextureController {
    private final TextureMapper textures;
    private final SiteOptions options;
    private final Path directory;
    private final TextureRenders renders;
    public TextureController(TextureMapper textures, SiteOptions options, @Value("${koharu.textures:storage/textures}") String directory,TextureRenders renders) {
        this.textures = textures;
        this.options = options;
        this.directory = Path.of(directory);
        this.renders=renders;
    }

    @GetMapping({"/skinlib/info/{tid}", "/texture/{tid}"})
    public Texture info(@PathVariable int tid, @AuthenticationPrincipal AccountUser user) {
        Texture texture = find(tid);
        if (!texture.visible() && (user == null || (user.uid() != texture.uploader() && user.permission() < 1))) {
            throw new ResponseStatusException(options.number("status_code_for_private", 403) == 404 ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN);
        }
        return texture;
    }

    private Texture find(int tid) {
        Texture texture = textures.find(tid);
        if (texture == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "材质不存在");
        return texture;
    }

    @GetMapping({"/{name}.json","/csl/{name}.json"})
    public ResponseEntity<java.util.Map<String,Object>> csl(@PathVariable String name,org.springframework.web.context.request.WebRequest request,jakarta.servlet.http.HttpServletRequest httpRequest) {
        var player=textures.cslProfile(name);
        if(player==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"角色不存在");
        if(player.permission()<0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"此角色所属账号已被封禁");
        var modified=player.lastModified().atZone(java.time.ZoneId.systemDefault()).toInstant();
        boolean notModified=request.checkNotModified(modified.toEpochMilli());
        var response=ResponseEntity.status(notModified?HttpStatus.NOT_MODIFIED:HttpStatus.OK)
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofSeconds(options.number("cache_expire_time",31536000))).cachePublic())
                .lastModified(modified).contentType(MediaType.APPLICATION_JSON);
        if(notModified||httpRequest.getMethod().equals("HEAD")) return response.build();
        var profile=new java.util.LinkedHashMap<String,Object>();
        profile.put("username",player.name());
        profile.put("skins",java.util.Collections.singletonMap("alex".equals(player.skinType())?"slim":"default",player.skinHash()));
        profile.put("cape",player.capeHash());
        return response.body(profile);
    }

    @GetMapping({"/textures/{hash}", "/csl/textures/{hash}"})
    public ResponseEntity<Resource> file(@PathVariable String hash) throws IOException {
        if (!hash.matches("[a-fA-F0-9]{64}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path file = directory.resolve(hash);
        if (!Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(Files.size(file))
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofSeconds(options.number("cache_expire_time",31536000))).cachePublic())
                .lastModified(Files.getLastModifiedTime(file).toMillis()).eTag(hash).body(new FileSystemResource(file));
    }

    @GetMapping("/raw/{tid}")
    public ResponseEntity<Resource> raw(@PathVariable int tid) throws IOException {
        if (!options.enabled("allow_downloading_texture", true)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return file(find(tid).hash());
    }

    @GetMapping(value = "/preview/{tid}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> preview(@PathVariable int tid, @RequestParam(defaultValue = "200") @Min(16) @Max(1024) int height) throws IOException {
        return image(renders.preview(find(tid),height));
    }

    @GetMapping(value="/preview/hash/{hash}",produces=MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewHash(@PathVariable String hash,@RequestParam(defaultValue="200") @Min(16) @Max(1024) int height) throws IOException {
        Integer tid=textures.idByHash(hash);
        if(tid==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"材质不存在");
        return preview(tid,height);
    }

    @GetMapping(value = "/avatar/{tid}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> avatar(@PathVariable int tid, @RequestParam(defaultValue = "100") @Min(8) @Max(512) int size,
                         @RequestParam java.util.Map<String, String> query) throws IOException {
        return image(renders.avatar(textures.find(tid),size,query.containsKey("3d")));
    }

    @GetMapping(value="/avatar/user/{uid}", produces=MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> userAvatar(@PathVariable long uid, @RequestParam(defaultValue="100") @Min(8) @Max(512) int size,
                             @RequestParam java.util.Map<String,String> query) throws IOException {
        Integer tid=textures.avatarId(uid);
        return avatar(tid==null?0:tid,size,query);
    }

    @GetMapping(value="/avatar/player/{name}", produces=MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> playerAvatar(@PathVariable String name, @RequestParam(defaultValue="100") @Min(8) @Max(512) int size,
                               @RequestParam java.util.Map<String,String> query) throws IOException {
        Integer tid=textures.playerSkin(name);
        if (tid==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"角色不存在");
        return avatar(tid,size,query);
    }

    @GetMapping(value="/avatar/hash/{hash}", produces=MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> hashAvatar(@PathVariable String hash, @RequestParam(defaultValue="100") @Min(8) @Max(512) int size,
                             @RequestParam java.util.Map<String,String> query) throws IOException {
        Integer tid=textures.idByHash(hash);
        return avatar(tid==null?0:tid,size,query);
    }

    private ResponseEntity<byte[]> image(TextureRenders.Image image) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(image.bytes().length)
                .cacheControl(org.springframework.http.CacheControl.noCache())
                .eTag(image.etag()).body(image.bytes());
    }
}
