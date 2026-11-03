package top.twinklestars.koharuskin.extension;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/extensions")
public class ExtensionController {
    private final OfficialExtensions extensions;
    public ExtensionController(OfficialExtensions extensions) { this.extensions=extensions; }
    @GetMapping("/data")
    public List<OfficialExtensions.Extension> list() { return extensions.list(); }
    @PutMapping("/{name}")
    public Map<String,Object> toggle(@PathVariable String name,@Valid @RequestBody Toggle input) {
        extensions.setEnabled(name,input.enabled());
        return Map.of("code",0,"message",input.enabled()?"官方扩展已启用":"官方扩展已停用");
    }
    public record Toggle(@NotNull Boolean enabled) {}
}
