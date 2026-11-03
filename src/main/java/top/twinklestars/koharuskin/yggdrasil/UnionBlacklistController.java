package top.twinklestars.koharuskin.yggdrasil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;
import java.util.Map;

@Controller
@RequestMapping("/admin/union")
public class UnionBlacklistController {
    private final UnionBlacklist blacklist;
    public UnionBlacklistController(UnionBlacklist blacklist) { this.blacklist=blacklist; }
    @GetMapping("/view/blacklist/list") @ResponseBody
    public JsonNode list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page) { return blacklist.query(q,page); }
    @PostMapping(value="/blacklist/create",consumes="application/json") @ResponseBody
    public Map<String,Object> create(@Valid @RequestBody Create input) { blacklist.create(input.email(),input.reason()); return Map.of("code",0,"message","已加入联盟黑名单"); }
    @PostMapping(value="/blacklist/create",consumes="application/x-www-form-urlencoded")
    public String createForm(@Valid @ModelAttribute("blacklistForm") Create input,BindingResult errors,RedirectAttributes flash) {
        if(errors.hasErrors()) flash.addFlashAttribute("error",errors.getAllErrors().getFirst().getDefaultMessage());
        else try { blacklist.create(input.email(),input.reason()); flash.addFlashAttribute("success","已加入联盟黑名单"); }
        catch(ResponseStatusException error) { flash.addFlashAttribute("error",error.getReason()); }
        if(flash.getFlashAttributes().containsKey("error")) flash.addFlashAttribute("blacklistForm",input);
        return "redirect:/admin/union/view/blacklist";
    }
    @PostMapping("/blacklist/{action:invalidate|delete}/{id}")
    public Object change(@PathVariable String action,@PathVariable long id,HttpServletRequest request,RedirectAttributes flash) {
        boolean json=request.getHeader("Accept")!=null&&request.getHeader("Accept").contains("application/json");
        try {
            blacklist.change(action,id);
            if(json) return ResponseEntity.ok(Map.of("code",0));
            flash.addFlashAttribute("success",action.equals("delete")?"已从联盟删除记录":"已释放记录，历史记录仍保留");
        } catch(ResponseStatusException error) {
            if(json) throw error;
            flash.addFlashAttribute("error",error.getReason());
        }
        return "redirect:/admin/union/view/blacklist";
    }
    @ExceptionHandler(ResponseStatusException.class) @ResponseBody
    public ResponseEntity<?> failure(ResponseStatusException error) { return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason())); }
    public record Create(@NotBlank(message="请输入邮箱") @Email(message="邮箱格式不正确") String email,
                         @NotBlank(message="请填写加入黑名单的原因") String reason) {}
}
