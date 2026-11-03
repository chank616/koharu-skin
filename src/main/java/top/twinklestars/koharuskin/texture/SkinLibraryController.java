package top.twinklestars.koharuskin.texture;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.site.PageResult;
import java.io.IOException;
import java.util.Map;

@RestController
public class SkinLibraryController {
    private final SkinLibraryMapper library;
    private final TextureService textures;
    public SkinLibraryController(SkinLibraryMapper library, TextureService textures) { this.library = library; this.textures = textures; }

    @GetMapping("/skinlib/list")
    public PageResult<SkinLibraryMapper.Item> list(@AuthenticationPrincipal AccountUser user,
            @RequestParam(defaultValue = "skin") @Pattern(regexp = "skin|steve|alex|cape") String filter,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(required = false) Long uploader,
            @RequestParam(defaultValue = "time") @Pattern(regexp = "time|likes") String sort,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page) {
        long uid = user == null ? -1 : user.uid();
        boolean admin = user != null && user.permission() >= 1;
        return PageResult.of(library.list(uid, admin, filter, keyword, uploader, sort, (page - 1) * 20), page, 20,
                library.count(uid, admin, filter, keyword, uploader));
    }

    @PostMapping("/texture")
    public Map<String, Object> upload(@AuthenticationPrincipal AccountUser user,
            @RequestParam @NotBlank @Size(max = 50) String name,
            @RequestParam @Pattern(regexp = "steve|alex|cape") String type,
            @RequestParam("public") @Pattern(regexp = "0|1|true|false") String visibility,
            @RequestParam MultipartFile file) throws IOException {
        return textures.upload(user, name, type, visibility.equals("1") || visibility.equals("true"), file);
    }

    @PutMapping("/texture/{tid}/name")
    public Map<String, Object> rename(@AuthenticationPrincipal AccountUser user, @PathVariable int tid, @Valid @RequestBody Rename input) {
        textures.rename(user, tid, input.name()); return success("名称已修改");
    }
    @PutMapping("/texture/{tid}/type")
    public Map<String, Object> type(@AuthenticationPrincipal AccountUser user, @PathVariable int tid, @Valid @RequestBody Type input) throws IOException {
        textures.type(user, tid, input.type()); return success("材质模型已修改");
    }
    @PutMapping("/texture/{tid}/privacy")
    public Map<String, Object> privacy(@AuthenticationPrincipal AccountUser user, @PathVariable int tid) { return textures.privacy(user, tid); }
    @DeleteMapping("/texture/{tid}")
    public Map<String, Object> delete(@AuthenticationPrincipal AccountUser user, @PathVariable int tid) {
        textures.delete(user, tid); return success("材质已删除");
    }
    @PostMapping("/user/profile/avatar")
    public Map<String, Object> avatar(@AuthenticationPrincipal AccountUser user, @Valid @RequestBody Avatar input) {
        textures.avatar(user, input.tid()); return success("头像已更新");
    }
    private Map<String, Object> success(String message) { return Map.of("code", 0, "message", message); }
    public record Rename(@NotBlank @Size(max = 50) String name) {}
    public record Type(@NotBlank @Pattern(regexp = "steve|alex|cape") String type) {}
    public record Avatar(@PositiveOrZero int tid) {}
}
