package com.example.chat.auth;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RegistrationApiIT {

    private static final String PASSWORD = "StudyChat2026!";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("chat_registration_test")
            .withUsername("testuser")
            .withPassword("testpassword");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearTestAccounts() {
        // DynamicPropertySource points this connection at the disposable test database.
        jdbcTemplate.update("DELETE FROM chat_users");
    }

    @Test
    void createsAccountWithNormalizedUsernameAndVerifiableHash() throws Exception {
        var result = register("Conor", PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("conor"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT username FROM chat_users WHERE id = ?", String.class, id
        )).isEqualTo("conor");

        String storedHash = hashFor("conor");
        assertThat(storedHash).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, storedHash)).isTrue();
        assertThat(passwordEncoder.matches("wrong-password", storedHash)).isFalse();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD, storedHash);
    }

    @Test
    void duplicateUsernameIsRejectedIgnoringCase() throws Exception {
        register("Conor", PASSWORD).andExpect(status().isCreated());
        register("CONOR", PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        assertThat(accountCount()).isEqualTo(1L);
    }

    @Test
    void equalPasswordsProduceDifferentStoredHashes() throws Exception {
        register("alice", PASSWORD).andExpect(status().isCreated());
        register("bob", PASSWORD).andExpect(status().isCreated());

        String aliceHash = hashFor("alice");
        String bobHash = hashFor("bob");
        // Random salts make the hashes different even though the original passwords match.
        assertThat(aliceHash).isNotEqualTo(bobHash);
        assertThat(passwordEncoder.matches(PASSWORD, aliceHash)).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, bobHash)).isTrue();
    }

    @Test
    void passwordWhitespaceIsPreserved() throws Exception {
        String password = "  " + PASSWORD + "  ";
        register("space_user", password).andExpect(status().isCreated());
        String storedHash = hashFor("space_user");
        assertThat(passwordEncoder.matches(password, storedHash)).isTrue();
        assertThat(passwordEncoder.matches(password.strip(), storedHash)).isFalse();
    }

    @Test
    void databaseAlsoRejectsDuplicateUsernames() throws Exception {
        register("conor", PASSWORD).andExpect(status().isCreated());
        String storedHash = hashFor("conor");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO chat_users (username, password_hash, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                "conor", storedHash
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidAccounts")
    void invalidInputDoesNotCreateAnAccount(String username, String password, String field) throws Exception {
        register(username, password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors." + field).exists());
        assertThat(accountCount()).isZero();
    }

    static Stream<Arguments> invalidAccounts() {
        return Stream.of(
                Arguments.of("ab", PASSWORD, "username"),
                Arguments.of("space name", PASSWORD, "username"),
                Arguments.of("x".repeat(31), PASSWORD, "username"),
                Arguments.of("valid_user", "short", "password"),
                Arguments.of("valid_user", " ".repeat(12), "password"),
                Arguments.of("valid_user", "x".repeat(129), "password")
        );
    }

    @Test
    void missingFieldsReturnValidationErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.username").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
        assertThat(accountCount()).isZero();
    }

    // Use a test CSRF token here; AuthSessionIT checks the real token-fetching flow separately.
    private ResultActions register(String username, String password) throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private String hashFor(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM chat_users WHERE username = ?", String.class, username
        );
    }

    private long accountCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM chat_users", Long.class);
    }
}
