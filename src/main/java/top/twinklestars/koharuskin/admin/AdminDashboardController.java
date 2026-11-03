package top.twinklestars.koharuskin.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminDashboardController {
    private final AdminDashboard dashboard;
    public AdminDashboardController(AdminDashboard dashboard) { this.dashboard=dashboard; }
    @GetMapping("/admin/chart")
    public AdminDashboard.Chart chart() { return dashboard.chart(); }
}
