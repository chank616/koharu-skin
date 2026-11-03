package top.twinklestars.koharuskin.account;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginAttempts {
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public int count(String address) {
        var value = attempts.get(address);
        return value == null || value.expiresAt().isBefore(Instant.now()) ? 0 : value.count();
    }

    public int failed(String address) {
        Instant now = Instant.now();
        attempts.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        return attempts.compute(address, (key, previous) -> new Attempt(
                previous == null || previous.expiresAt().isBefore(now) ? 1 : previous.count() + 1,
                now.plusSeconds(3600))).count();
    }

    public void clear(String address) { attempts.remove(address); }

    private record Attempt(int count, Instant expiresAt) {}
}
