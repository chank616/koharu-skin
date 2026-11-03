package top.twinklestars.koharuskin.player;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
@DependsOnDatabaseInitialization
public class UuidMigration implements InitializingBean {
    private final JdbcTemplate jdbc;
    public UuidMigration(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override public void afterPropertiesSet() {
        var columns=Set.copyOf(jdbc.queryForList("""
                SELECT LOWER(column_name) FROM information_schema.columns
                WHERE LOWER(table_schema)=LOWER(SCHEMA()) AND LOWER(table_name)='uuid'
                """,String.class));
        if(!columns.contains("pid")) jdbc.execute("ALTER TABLE uuid ADD COLUMN pid BIGINT NULL");
        if(!columns.contains("created_at")) jdbc.execute("ALTER TABLE uuid ADD COLUMN created_at TIMESTAMP NULL");
        if(!columns.contains("updated_at")) jdbc.execute("ALTER TABLE uuid ADD COLUMN updated_at TIMESTAMP NULL");
        // 保留旧 UUID 和重复记录；只有角色名唯一对应时才补充 Janus 所需的角色 ID。
        jdbc.update("""
                UPDATE uuid SET pid=(SELECT MIN(p.pid) FROM players p WHERE p.name=uuid.name HAVING COUNT(*)=1)
                WHERE pid IS NULL
                """);
    }
}
