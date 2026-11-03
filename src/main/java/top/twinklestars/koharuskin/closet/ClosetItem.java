package top.twinklestars.koharuskin.closet;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ClosetItem(int tid, String name, String type, String hash, int size, long uploader,
                         @JsonProperty("public") boolean visible, @JsonProperty("upload_at") LocalDateTime uploadAt,
                         int likes, @JsonIgnore String itemName, @JsonIgnore long userUid) {
    @JsonProperty("pivot")
    public Pivot pivot() { return new Pivot(userUid, tid, itemName); }
    public record Pivot(@JsonProperty("user_uid") long uid, @JsonProperty("texture_tid") int tid,
                        @JsonProperty("item_name") String name) {}
}
