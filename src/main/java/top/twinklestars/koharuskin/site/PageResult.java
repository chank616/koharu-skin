package top.twinklestars.koharuskin.site;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PageResult<T>(List<T> data, @JsonProperty("current_page") int currentPage,
                            @JsonProperty("last_page") int lastPage, Integer from, Integer to, int total) {
    public static <T> PageResult<T> of(List<T> data, int page, int size, int total) {
        int offset = (page - 1) * size;
        return new PageResult<>(data, page, Math.max(1, (total + size - 1) / size),
                data.isEmpty() ? null : offset + 1, data.isEmpty() ? null : offset + data.size(), total);
    }
}
