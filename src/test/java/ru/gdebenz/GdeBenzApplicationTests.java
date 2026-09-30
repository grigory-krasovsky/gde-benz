package ru.gdebenz;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: verifies the Spring application context starts.
 *
 * <p>{@code telegrambots.enabled=false} keeps the bot from opening a real Telegram connection, a
 * dummy token lets the {@link ru.gdebenz.bot.GdeBenzBot} bean be created, and an in-memory H2
 * database stands in for PostgreSQL so the test has no external dependencies.
 */
@SpringBootTest(properties = {
        "telegrambots.enabled=false",
        "telegram.bot.token=123456:TEST-CONTEXT-LOAD",
        "spring.datasource.url=jdbc:h2:mem:gdebenz;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class GdeBenzApplicationTests {

    @Test
    void contextLoads() {
    }
}
