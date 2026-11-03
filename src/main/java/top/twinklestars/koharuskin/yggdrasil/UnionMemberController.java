package top.twinklestars.koharuskin.yggdrasil;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;

@RestController
@RequestMapping("/api/union/member")
public class UnionMemberController {
    private final UnionMember union;
    private final JsonMapper json;
    public UnionMemberController(UnionMember union,JsonMapper json) { this.union=union; this.json=json; }
    @CrossOrigin(origins="*")
    @GetMapping({"","/"}) public Map<String,Object> hello() { return union.hello(); }
    @PostMapping({"/updatelist","/updateprivatekey","/updatebackendkey","/sync","/remapuuid","/diagnose"})
    public ResponseEntity<?> command(HttpServletRequest request,@RequestBody(required=false) byte[] body) {
        byte[] raw=body==null?new byte[0]:body;
        union.verify(request,raw);
        var input=raw.length==0?json.createObjectNode():json.readTree(raw);
        switch(request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/')+1)) {
            case "updatelist" -> union.updateList();
            case "updateprivatekey" -> union.updatePrivateKey();
            case "updatebackendkey" -> union.updateMemberKey(input.path("key").asString(""));
            case "sync" -> union.sync();
            case "remapuuid" -> union.remap(input.path("remapped_uuid"));
            case "diagnose" -> { return ResponseEntity.ok(union.diagnose(input)); }
        }
        return ResponseEntity.ok().build();
    }
    @GetMapping("/queryemail") public ResponseEntity<?> email(HttpServletRequest request,@RequestParam String username) {
        union.verify(request,new byte[0]);
        String email=union.email(username);
        return email==null?ResponseEntity.noContent().build():ResponseEntity.ok(Map.of("email",email));
    }
    @ExceptionHandler(tools.jackson.core.JacksonException.class)
    public ResponseEntity<?> invalidJson() { return ResponseEntity.badRequest().body(Map.of("message","JSON 格式无效")); }
}
