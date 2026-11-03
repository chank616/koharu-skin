package top.twinklestars.koharuskin.yggdrasil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.account.BusinessException;
import top.twinklestars.koharuskin.closet.ClosetService;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.player.PlayerMapper;
import top.twinklestars.koharuskin.player.PlayerService;
import top.twinklestars.koharuskin.site.SiteOptions;
import top.twinklestars.koharuskin.texture.TextureService;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/yggdrasil")
public class YggController {
    private final YggAuth auth;
    private final YggProfiles profiles;
    private final YggMapper mapper;
    private final PlayerMapper players;
    private final PlayerService playerService;
    private final OfficialExtensions extensions;
    private final SiteOptions options;
    private final TextureService textures;
    private final ClosetService closet;
    private final YggKeys keys;

    public YggController(YggAuth auth,YggProfiles profiles,YggMapper mapper,PlayerMapper players,PlayerService playerService,
                         OfficialExtensions extensions,SiteOptions options,TextureService textures,ClosetService closet,YggKeys keys) {
        this.auth=auth; this.profiles=profiles; this.mapper=mapper; this.players=players; this.playerService=playerService;
        this.extensions=extensions; this.options=options; this.textures=textures; this.closet=closet;
        this.keys=keys;
    }

    @ModelAttribute public void enabled(HttpServletRequest request) {
        if (!extensions.enabled("yggdrasil-api")&&!extensions.enabled("yggdrasil-connect")) throw new YggException(404,"Not Found","External authentication is disabled.");
        String route=(String)request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if(route.startsWith("/api/yggdrasil/authserver/")&&options.enabled("ygg_disable_authserver",false))
            throw YggException.forbidden("Traditional authentication is disabled. Use OpenID Connect to sign in.");
    }

    @GetMapping({"","/"}) public Map<String,Object> metadata() { return profiles.metadata(); }

    @GetMapping("/restore") public Map<String,String> restoreHello() { return Map.of("status","success"); }
    @PostMapping(value="/restore",consumes=MediaType.APPLICATION_JSON_VALUE)
    public tools.jackson.databind.node.ObjectNode restore(@RequestBody tools.jackson.databind.node.ObjectNode profile) {
        if(!options.enabled("ygg_restore_api",false)) throw YggException.forbidden("Restore API is disabled.");
        if(!profile.path("properties").isArray()) throw YggException.badRequest("Profile properties must be an array.");
        for(var property:profile.get("properties"))
            if(!property.isObject()||!property.path("value").isString()) throw YggException.badRequest("Property value must be a string.");
        var key=keys.pair().getPrivate();
        for(var property:profile.get("properties"))
            ((tools.jackson.databind.node.ObjectNode)property).put("signature",keys.sign(property.get("value").asString(),key));
        return profile;
    }

    @PostMapping(value="/authserver/authenticate",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> authenticate(@Valid @RequestBody Login input,HttpServletRequest request) {
        return auth.authenticate(input.username(),input.password(),input.clientToken(),Boolean.TRUE.equals(input.requestUser()),request.getRemoteAddr());
    }
    @PostMapping(value="/authserver/refresh",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> refresh(@Valid @RequestBody Refresh input,HttpServletRequest request) {
        var profile=input.selectedProfile();
        return auth.refresh(input.accessToken(),input.clientToken(),profile==null?null:profile.id(),profile==null?null:profile.name(),Boolean.TRUE.equals(input.requestUser()),request.getRemoteAddr());
    }
    @PostMapping(value="/authserver/validate",consumes=MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.NO_CONTENT)
    public void validate(@Valid @RequestBody TokenInput input,HttpServletRequest request) { auth.validate(input.accessToken(),input.clientToken(),request.getRemoteAddr()); }
    @PostMapping(value="/authserver/invalidate",consumes=MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.NO_CONTENT)
    public void invalidate(@Valid @RequestBody TokenInput input,HttpServletRequest request) { auth.invalidate(input.accessToken(),request.getRemoteAddr()); }
    @PostMapping(value="/authserver/signout",consumes=MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signout(@Valid @RequestBody Login input,HttpServletRequest request) { auth.signout(input.username(),input.password(),request.getRemoteAddr()); }
    @PostMapping(value="/sessionserver/session/minecraft/join",consumes=MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.NO_CONTENT)
    public void join(@Valid @RequestBody Join input,HttpServletRequest request) {
        auth.join(input.accessToken(),input.selectedProfile(),input.serverId(),request.getRemoteAddr());
    }
    @GetMapping("/sessionserver/session/minecraft/hasJoined")
    public ResponseEntity<?> hasJoined(@RequestParam String username,@RequestParam String serverId,@RequestParam(required=false) String ip,HttpServletRequest request) {
        var profile=auth.hasJoined(username,serverId,ip,request.getRemoteAddr());
        return profile==null?ResponseEntity.noContent().build():ResponseEntity.ok(profile);
    }
    @GetMapping("/sessionserver/session/minecraft/profile/{uuid}")
    public ResponseEntity<?> profile(@PathVariable String uuid,@RequestParam(defaultValue="true") boolean unsigned) {
        var player=mapper.profile(uuid);
        return player==null?ResponseEntity.noContent().build():ResponseEntity.ok(profiles.full(player,unsigned));
    }
    @GetMapping({"/api/users/profiles/minecraft/{name}","/minecraftservices/minecraft/profile/lookup/name/{name}"})
    public ResponseEntity<?> search(@PathVariable String name) {
        var player=players.findByName(name);
        return player==null?ResponseEntity.noContent().build():ResponseEntity.ok(profiles.simple(player));
    }
    @PostMapping(value={"/api/profiles/minecraft","/minecraftservices/minecraft/profile/lookup/bulk/byname"},consumes=MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String,String>> search(@Valid @RequestBody List<@NotBlank String> names) {
        var unique=new LinkedHashSet<>(names);
        if (unique.size()>options.number("ygg_search_profile_max",5)) throw YggException.badRequest("Too many profile names.");
        return unique.stream().map(players::findByName).filter(Objects::nonNull).map(profiles::simple).toList();
    }

    @PutMapping(value="/api/user/profile/{uuid}/{type}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.NO_CONTENT)
    @org.springframework.transaction.annotation.Transactional(rollbackFor=IOException.class)
    public void upload(@PathVariable String uuid,@PathVariable String type,@RequestHeader(value="Authorization",required=false) String authorization,
                       @RequestParam(defaultValue="") String model,@RequestParam MultipartFile file) throws IOException {
        var user=auth.bearer(authorization,uuid);
        if (!type.equals("skin") && !type.equals("cape")) throw YggException.badRequest("Unknown texture type.");
        if (!model.isEmpty() && !model.equals("slim")) throw YggException.badRequest("Unknown skin model.");
        String name=file.getOriginalFilename();
        if (name==null || name.isBlank()) name="Minecraft texture";
        name=name.replaceAll("(?i)\\.png$","");
        int tid=textures.uploadPrivate(user,name,type.equals("cape")?"cape":model.equals("slim")?"alex":"steve",file);
        if (players.inCloset(user.uid(),tid)==0) closet.add(user,tid,name);
        playerService.setTextures(user.uid(),mapper.profile(uuid).pid(),type.equals("skin")?tid:null,type.equals("cape")?tid:null);
    }
    @DeleteMapping("/api/user/profile/{uuid}/{type}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String uuid,@PathVariable String type,@RequestHeader(value="Authorization",required=false) String authorization) {
        var user=auth.bearer(authorization,uuid);
        if (!type.equals("skin") && !type.equals("cape")) throw YggException.badRequest("Unknown texture type.");
        playerService.setTextures(user.uid(),mapper.profile(uuid).pid(),type.equals("skin")?0:null,type.equals("cape")?0:null);
    }

    @ExceptionHandler(YggException.class)
    public ResponseEntity<?> error(YggException error) { return ResponseEntity.status(error.status).body(Map.of("error",error.error,"errorMessage",error.getMessage())); }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException error) { return error(YggException.forbidden(error.getMessage())); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",error.getStatusCode().toString(),"errorMessage",Objects.toString(error.getReason(),"Request rejected.")));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    public ResponseEntity<?> invalidRequest(Exception error) { return error(YggException.badRequest("Invalid request parameters.")); }

    public record Login(@NotBlank @Size(max=100) String username,@NotNull @Size(max=32) String password,String clientToken,Boolean requestUser) {}
    public record ProfileInput(@NotBlank String id,@NotBlank String name) {}
    public record Refresh(@NotBlank String accessToken,String clientToken,@Valid ProfileInput selectedProfile,Boolean requestUser) {}
    public record TokenInput(@NotBlank String accessToken,String clientToken) {}
    public record Join(@NotBlank String accessToken,@NotBlank String selectedProfile,@NotBlank String serverId) {}
}
