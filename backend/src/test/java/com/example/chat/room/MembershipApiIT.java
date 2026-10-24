package com.example.chat.room;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.chat.auth.RegisterRequest;
import com.example.chat.auth.RegistrationService;
import com.example.chat.auth.UserResponse;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MembershipApiIT {

    private static final String PASSWORD = "StudyChat2026!";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.withDatabaseName("chat_membership_test");
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

    @Autowired
    private RoomMembershipService membershipService;

    private UserResponse alice;
    private UserResponse bob;

    @BeforeEach
    void resetTestData() {
        // This connection points only to the disposable Testcontainers database.
        jdbcTemplate.update("DELETE FROM room_members");
        jdbcTemplate.update("DELETE FROM chat_rooms");
        jdbcTemplate.update("DELETE FROM chat_users");
        alice = registrationService.register(new RegisterRequest("alice", PASSWORD));
        bob = registrationService.register(new RegisterRequest("bob", PASSWORD));
    }

    @Test
    void creatingARoomAlsoJoinsItsCreator() throws Exception {
        Long roomId = createRoom();
        mockMvc.perform(get(membershipUrl(roomId)).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.member").value(true));
        assertThat(membershipCount(roomId, alice.id())).isEqualTo(1L);
        assertThat(membershipCount(roomId, bob.id())).isZero();
        membershipService.requireMember(roomId, "alice");
    }

    @Test
    void nonmembersCanBrowsePublicRoomDetailsButFailTheMessageMembershipRule() throws Exception {
        Long roomId = createRoom();
        mockMvc.perform(get("/api/rooms/{id}", roomId).with(user("bob")))
                .andExpect(status().isOk());
        mockMvc.perform(get(membershipUrl(roomId)).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value(false));
        assertThatThrownBy(() -> membershipService.requireMember(roomId, "bob"))
                .isInstanceOf(RoomMembershipRequiredException.class);
    }

    @Test
    void repeatingAJoinDoesNotCreateDuplicateMemberships() throws Exception {
        Long roomId = createRoom();
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(membershipUrl(roomId)).with(user("bob")).with(csrf()))
                    .andExpect(status().isNoContent());
        }
        assertThat(membershipCount(roomId, bob.id())).isEqualTo(1L);
        assertThat(membershipCount(roomId, alice.id())).isEqualTo(1L);
        membershipService.requireMember(roomId, "bob");
    }

    @Test
    void leavingIsRepeatableAndDoesNotChangeAnotherUsersMembership() throws Exception {
        Long roomId = createRoom();
        membershipService.join(roomId, "bob");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete(membershipUrl(roomId)).with(user("bob")).with(csrf()))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(get(membershipUrl(roomId)).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value(false));
        assertThat(membershipCount(roomId, bob.id())).isZero();
        assertThat(membershipCount(roomId, alice.id())).isEqualTo(1L);
        assertThatThrownBy(() -> membershipService.requireMember(roomId, "bob"))
                .isInstanceOf(RoomMembershipRequiredException.class);
    }

    @Test
    void aRoomCreatorCanLeaveAndRejoin() throws Exception {
        Long roomId = createRoom();
        mockMvc.perform(delete(membershipUrl(roomId)).with(user("alice")).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(membershipUrl(roomId)).with(user("alice")))
                .andExpect(jsonPath("$.member").value(false));
        mockMvc.perform(post(membershipUrl(roomId)).with(user("alice")).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(membershipCount(roomId, alice.id())).isEqualTo(1L);
    }

    @Test
    void aUserIdInTheBodyCannotJoinAnotherAccount() throws Exception {
        Long roomId = createRoom();
        mockMvc.perform(post(membershipUrl(roomId)).with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + bob.id() + "}"))
                .andExpect(status().isNoContent());
        assertThat(membershipCount(roomId, alice.id())).isEqualTo(1L);
        assertThat(membershipCount(roomId, bob.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void missingRoomsReturn404(String method) throws Exception {
        mockMvc.perform(membershipRequest(method, Long.MAX_VALUE).with(user("alice")).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void anonymousClientsCannotReadOrChangeMembership(String method) throws Exception {
        Long roomId = createRoom();
        // A valid test token lets this specifically test authentication.
        mockMvc.perform(membershipRequest(method, roomId).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE"})
    void membershipChangesRequireCsrf(String method) throws Exception {
        Long roomId = createRoom();
        mockMvc.perform(membershipRequest(method, roomId).with(user("bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF"));
        assertThat(membershipCount(roomId, bob.id())).isZero();
    }

    @Test
    void roomsCreatedBeforeMembershipWasAddedCanStillBeJoined() throws Exception {
        Long roomId = jdbcTemplate.queryForObject(
                "INSERT INTO chat_rooms (name, created_at) VALUES ('Older Room', CURRENT_TIMESTAMP) RETURNING id",
                Long.class
        );
        mockMvc.perform(get(membershipUrl(roomId)).with(user("alice")))
                .andExpect(jsonPath("$.member").value(false));
        mockMvc.perform(post(membershipUrl(roomId)).with(user("alice")).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(membershipCount(roomId, alice.id())).isEqualTo(1L);
    }

    @Test
    void theDatabaseRejectsDuplicatePairsAndInvalidForeignKeys() throws Exception {
        Long roomId = createRoom();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO room_members (room_id, user_id, joined_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                roomId, alice.id()
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO room_members (room_id, user_id, joined_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                roomId, Long.MAX_VALUE
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO room_members (room_id, user_id, joined_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                Long.MAX_VALUE, bob.id()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void twoSimultaneousJoinsCreateOneMembership() throws Exception {
        Long roomId = createRoom();
        // Both workers reach the starting line before either is allowed to join.
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Void> join = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent join did not start in time.");
            }
            membershipService.join(roomId, "bob");
            return null;
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(join);
            var second = executor.submit(join);
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                // Release them together to exercise the database's ON CONFLICT handling.
                start.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                start.countDown();
                first.cancel(true);
                second.cancel(true);
                executor.shutdownNow();
            }
        }
        assertThat(membershipCount(roomId, bob.id())).isEqualTo(1L);
    }

    private Long createRoom() throws Exception {
        var result = mockMvc.perform(post("/api/rooms").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"General\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long membershipCount(Long roomId, Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM room_members WHERE room_id = ? AND user_id = ?",
                Long.class, roomId, userId
        );
    }

    private String membershipUrl(Long roomId) {
        return "/api/rooms/" + roomId + "/members/me";
    }

    private MockHttpServletRequestBuilder membershipRequest(String method, Long roomId) {
        return switch (method) {
            case "GET" -> get(membershipUrl(roomId));
            case "POST" -> post(membershipUrl(roomId));
            case "DELETE" -> delete(membershipUrl(roomId));
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
    }
}
