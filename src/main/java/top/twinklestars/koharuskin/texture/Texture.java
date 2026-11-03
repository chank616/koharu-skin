package top.twinklestars.koharuskin.texture;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record Texture(int tid, String name, String type, String hash, int size, long uploader,
                      @JsonProperty("public") boolean visible, @JsonProperty("upload_at") LocalDateTime uploadAt, int likes) {}
