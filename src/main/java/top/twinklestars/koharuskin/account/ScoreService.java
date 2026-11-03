package top.twinklestars.koharuskin.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ScoreService {
    private final ScoreMapper scores;
    private final SiteOptions options;
    public ScoreService(ScoreMapper scores, SiteOptions options) { this.scores = scores; this.options = options; }

    public Map<String, Object> info(long uid) {
        var balance = scores.balance(uid);
        return Map.of("user", Map.of("score", balance.score(), "lastSignAt", balance.lastSignAt().atZone(ZoneId.systemDefault()).toInstant()),
                "rate", Map.of("storage", options.number("score_per_storage", 1), "players", options.number("score_per_player", 100)),
                "usage", Map.of("players", scores.players(uid), "storage", scores.storage(uid)),
                "signAfterZero", options.enabled("sign_after_zero", false), "signGapTime", options.number("sign_gap_time", 24));
    }

    @Transactional
    public Map<String, Object> sign(long uid) {
        var balance = scores.lock(uid);
        var now = LocalDateTime.now();
        boolean eligible = options.enabled("sign_after_zero", false)
                ? balance.lastSignAt().toLocalDate().isBefore(now.toLocalDate())
                : !now.isBefore(balance.lastSignAt().plusHours(options.number("sign_gap_time", 24)));
        if (!eligible) return Map.of("code", 1, "message", "尚未到下次签到时间");
        String[] range = options.text("sign_score", "10,100").split(",");
        int amount = ThreadLocalRandom.current().nextInt(Integer.parseInt(range[0]), Integer.parseInt(range[1]) + 1);
        scores.sign(uid, amount, now);
        return Map.of("code", 0, "message", "签到成功，获得 " + amount + " 积分", "data", Map.of("score", balance.score() + amount));
    }
}
