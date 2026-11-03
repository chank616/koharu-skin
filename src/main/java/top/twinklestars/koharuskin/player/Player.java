package top.twinklestars.koharuskin.player;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record Player(long pid, long uid, String name,
                     @JsonProperty("tid_skin") int tidSkin, @JsonProperty("tid_cape") int tidCape,
                     @JsonProperty("last_modified") LocalDateTime lastModified) {}
