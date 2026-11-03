package top.twinklestars.koharuskin.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record OAuthClient(long id, @JsonProperty("user_id") Long userId, String name, String secret,
                          String provider, String redirect,
                          @JsonProperty("personal_access_client") boolean personalAccessClient,
                          @JsonProperty("password_client") boolean passwordClient, boolean revoked,
                          @JsonProperty("created_at") LocalDateTime createdAt,
                          @JsonProperty("updated_at") LocalDateTime updatedAt) {}
