package com.example.chat.room;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.chat.auth.RegisterRequest;
import com.example.chat.auth.RegistrationService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
// Supply the security identity here, then create its matching database row before each test.
@WithMockUser(username = "room_tester")
class RoomApiIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.withDatabaseName("chat_test");
        POSTGRES.withUsername("testuser");
        POSTGRES.withPassword("testpassword");
    }

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
    private RegistrationService registrationService;

    @BeforeEach
    void clearTestRooms() {
        // This connection points only to the disposable Testcontainers database.
        jdbcTemplate.update("DELETE FROM room_members");
        jdbcTemplate.update("DELETE FROM chat_rooms");
        jdbcTemplate.update("DELETE FROM chat_users");
        registrationService.register(new RegisterRequest("room_tester", "StudyChat2026!"));
    }

    @Test
    void createPersistsTrimmedNameAndReturnsALocation() throws Exception {
        MvcResult result = createRoom("  General  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("General"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andReturn();

        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/rooms/" + id);

        mockMvc.perform(get("/api/rooms/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("General"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM chat_rooms WHERE id = ?", String.class, id
        )).isEqualTo("General");
    }

    @Test
    void duplicateNameIsRejectedIgnoringCase() throws Exception {
        createRoom("General").andExpect(status().isCreated());

        createRoom(" general ")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_NAME_TAKEN"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_rooms", Long.class)).isEqualTo(1L);
    }

    @Test
    void databaseAlsoEnforcesCaseInsensitiveUniqueness() throws Exception {
        createRoom("General").andExpect(status().isCreated());

        // Go around the service to prove the database itself rejects the duplicate too.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO chat_rooms (name, created_at) VALUES (?, CURRENT_TIMESTAMP)", "GENERAL"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void invalidNamesReturnBadRequest(String name) throws Exception {
        createRoom(name)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
    }

    static Stream<String> invalidNames() {
        return Stream.of("", "   ", "x".repeat(81));
    }

    @Test
    void missingNameReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/rooms").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void missingRoomReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/rooms/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    void listReturnsBoundedPagesWithNewestRoomsFirst() throws Exception {
        createRoom("First").andExpect(status().isCreated());
        createRoom("Second").andExpect(status().isCreated());
        createRoom("Third").andExpect(status().isCreated());

        mockMvc.perform(get("/api/rooms").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Third"))
                .andExpect(jsonPath("$.items[1].name").value("Second"))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/rooms").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("First"));
    }

    @Test
    void invalidPaginationReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/rooms").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/rooms").param("size", "51"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/rooms").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/rooms").param("page", "not-a-number"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions createRoom(String name) throws Exception {
        return mockMvc.perform(post("/api/rooms")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", name))));
    }
}
