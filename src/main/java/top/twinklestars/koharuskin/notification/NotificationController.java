package top.twinklestars.koharuskin.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import top.twinklestars.koharuskin.account.*;
import java.util.List;
import java.util.Map;

@Controller
public class NotificationController {
    private final NotificationService notifications;
    public NotificationController(NotificationService notifications) { this.notifications=notifications; }
    @GetMapping("/api/user/notifications") @ResponseBody
    public List<NotificationService.Summary> unread(@AuthenticationPrincipal AccountUser user) { return notifications.unread(user.uid()); }
    @PostMapping({"/user/notifications/{id}","/api/user/notifications/{id}"}) @ResponseBody
    public NotificationService.Detail read(@AuthenticationPrincipal AccountUser user,@PathVariable String id) { return notifications.read(user.uid(),id); }
    public record Send(@NotNull @Pattern(regexp="all|normal|uid|email") String receiver,Long uid,String email,String title,String content) {}
    @PostMapping("/admin/notifications/send")
    public String send(@AuthenticationPrincipal AccountUser admin,@Valid @ModelAttribute("notificationForm") Send input,BindingResult errors,RedirectAttributes flash) {
        if(errors.hasErrors()) {
            flash.addFlashAttribute("notificationError","请检查接收者和用户 ID"); flash.addFlashAttribute("notificationForm",input);
        } else {
            try {
                notifications.send(admin,input.receiver(),input.uid(),input.email(),input.title(),input.content());
                flash.addFlashAttribute("notificationSent",true);
            } catch(BusinessException error) {
                flash.addFlashAttribute("notificationError",error.getMessage()); flash.addFlashAttribute("notificationForm",input);
            }
        }
        return "redirect:/admin";
    }
    @PostMapping("/api/admin/notifications") @ResponseBody
    public Map<String,Object> sendApi(@AuthenticationPrincipal AccountUser admin,@Valid @RequestBody Send input) {
        int count=notifications.send(admin,input.receiver(),input.uid(),input.email(),input.title(),input.content());
        return Map.of("code",0,"message","通知发送成功","data",Map.of("count",count));
    }
}
