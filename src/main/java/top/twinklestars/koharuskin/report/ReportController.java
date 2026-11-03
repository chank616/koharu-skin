package top.twinklestars.koharuskin.report;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import top.twinklestars.koharuskin.account.AccountUser;
import java.util.Map;

@RestController
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports) { this.reports=reports; }
    @PostMapping("/skinlib/report")
    public Map<String,Object> submit(@AuthenticationPrincipal AccountUser user,@Valid @RequestBody Submission input) {
        reports.submit(user,input.tid(),input.reason()); return Map.of("code",0,"message","举报已提交，请等待管理员审核");
    }
    @GetMapping("/admin/reports/list")
    public Map<String,Object> list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page) { return reports.manage(q,page); }
    @PutMapping("/admin/reports/{id}")
    public Map<String,Object> review(@AuthenticationPrincipal AccountUser admin,@PathVariable long id,@Valid @RequestBody Review input) {
        int status=reports.review(admin,id,input.action());
        return Map.of("code",0,"message","举报已处理","data",Map.of("status",status));
    }
    public record Submission(@NotNull @Positive Integer tid,@NotBlank String reason) {}
    public record Review(@NotNull @Pattern(regexp="delete|ban|reject") String action) {}
}
