package top.twinklestars.koharuskin.yggdrasil;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import top.twinklestars.koharuskin.account.AccountUser;
import java.util.Map;

@RestController
@RequestMapping("/union")
public class UnionProfilesController {
    private final UnionProfiles profiles;
    public UnionProfilesController(UnionProfiles profiles) { this.profiles=profiles; }
    @PostMapping("/bind") public Map<String,String> bind(@AuthenticationPrincipal AccountUser user,@Valid @RequestBody Profile input) { return profiles.bind(user.uid(),input.uuid()); }
    @PostMapping("/bindto") public void bindTo(@AuthenticationPrincipal AccountUser user,@Valid @RequestBody Bind input) { profiles.bindTo(user.uid(),input.uuid(),input.token()); }
    @PostMapping("/unbind") public void unbind(@AuthenticationPrincipal AccountUser user,@Valid @RequestBody Profile input) { profiles.unbind(user.uid(),input.uuid()); }
    @PostMapping("/remapuuid") public void remap(@AuthenticationPrincipal AccountUser user,@Valid @RequestBody Remap input) { profiles.remap(user.uid(),input.me(),input.target()); }
    @GetMapping("/security/level") public tools.jackson.databind.JsonNode level() { return profiles.securityLevel(); }
    @GetMapping("/description") public Map<String,String> description() { return profiles.description(); }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> failure(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()));
    }
    public record Profile(@NotBlank String uuid) {}
    public record Bind(@NotBlank String uuid,@NotBlank String token) {}
    public record Remap(@NotBlank String me,@NotBlank String target) {}
}
