package top.twinklestars.koharuskin.player;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class UuidMigrationTests {
    @Test void oldSchemaKeepsDuplicateAndOrphanUuidsAndOnlyLinksUnambiguousPlayers() {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE players(pid INT PRIMARY KEY,name VARCHAR_IGNORECASE(255))");
        jdbc.execute("CREATE TABLE uuid(id INT PRIMARY KEY,name VARCHAR_IGNORECASE(255),uuid VARCHAR(255))");
        jdbc.update("INSERT INTO players VALUES(1,'PlayerOne'),(2,'RepeatedPlayer'),(3,'repeatedplayer')");
        jdbc.update("INSERT INTO uuid VALUES(1,'playerone','original-uuid'),(2,'PlayerOne','another-old-uuid'),(3,'DeletedPlayer','orphan-uuid'),(4,'RepeatedPlayer','ambiguous-uuid')");
        var before=jdbc.queryForList("SELECT id,name,uuid FROM uuid ORDER BY id");
        var migration=new UuidMigration(jdbc);migration.afterPropertiesSet();
        assertThat(jdbc.queryForList("SELECT id,name,uuid FROM uuid ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT pid FROM uuid ORDER BY id",Long.class)).containsExactly(1L,1L,null,null);
        jdbc.update("UPDATE uuid SET pid=99,created_at='2020-01-01 00:00:00',updated_at='2020-02-01 00:00:00' WHERE id=3");
        var migrated=jdbc.queryForList("SELECT * FROM uuid ORDER BY id");
        migration.afterPropertiesSet();
        assertThat(jdbc.queryForList("SELECT * FROM uuid ORDER BY id")).isEqualTo(migrated);
    }
}
