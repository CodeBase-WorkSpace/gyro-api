package com.gyro.api.food

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class ContributorDemoSeedTest {
    @Test
    fun `synthetic food fixture runs twice without duplicating rows or creating accounts`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("contributor_db")
            .withUsername("contributor")
            .withPassword("local-test-only")
            .use { postgres ->
                postgres.start()
                Flyway.configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .load()
                    .migrate()

                val fixture = Files.readString(Path.of("scripts/seed-contributor-demo.sql"))
                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                    connection.createStatement().use { statement ->
                        repeat(2) { statement.execute(fixture) }

                        statement.executeQuery(
                            "select count(*) from foods where public_id in ('example-oat-bowl', 'example-lentil-soup')",
                        ).use { result ->
                            result.next()
                            assertEquals(2, result.getInt(1))
                        }
                        statement.executeQuery(
                            "select count(*) from food_search_terms where food_id in " +
                                "(select id from foods where public_id in ('example-oat-bowl', 'example-lentil-soup'))",
                        ).use { result ->
                            result.next()
                            assertEquals(2, result.getInt(1))
                        }
                        statement.executeQuery("select count(*) from users").use { result ->
                            result.next()
                            assertEquals(0, result.getInt(1))
                        }
                    }
                }
            }
    }
}
