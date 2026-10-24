package com.example.chat.message;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
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
import com.example.chat.room.RoomMembershipService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MessageApiIT {

    private static final String PASSWORD = "StudyChat2026!";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("chat_message_test")
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
    private RegistrationService registrationService;

    @Autowired
    private RoomMembershipService membershipService;

    private UserResponse alice;
    private UserResponse bob;

    @BeforeEach
    void resetTestData() {
        // DynamicPropertySource points these deletes only at a disposable test database.
        jdbcTemplate.update("DELETE FROM chat_messages");
        jdbcTemplate.update("DELETE FROM room_members");
        jdbcTemplate.update("DELETE FROM chat_rooms");
        jdbcTemplate.update("DELETE FROM chat_users");
        alice = registrationService.register(new RegisterRequest("alice", PASSWORD));
        bob = registrationService.register(new RegisterRequest("bob", PASSWORD));
    }

    @Test
    void aMemberCanSaveAMessageAndReadItInANewRequest() throws Exception {
        Long roomId = createRoom("General");
        String content = "  Hello, room!\nSecond line.  ";
        var result = mockMvc.perform(post(messagesUrl(roomId)).with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.senderId").value(alice.id()))
                .andExpect(jsonPath("$.senderUsername").value("alice"))
                .andExpect(jsonPath("$.content").value(content))
                .andExpect(jsonPath("$.createdAt").isString())
                .andReturn();
        Long messageId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("passwordHash", PASSWORD);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM chat_messages WHERE id = ?", String.class, messageId
        )).isEqualTo(content);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sender_id FROM chat_messages WHERE id = ?", Long.class, messageId
        )).isEqualTo(alice.id());

        // This request uses a separate transaction, so it must read the saved row.
        mockMvc.perform(get(messagesUrl(roomId)).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(messageId))
                .andExpect(jsonPath("$.items[0].content").value(content))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextBefore").value(nullValue()));
    }

    @Test
    void extraJsonCannotImpersonateAnotherSenderOrChooseAnotherRoomOrTime() throws Exception {
        Long roomId = createRoom("General");
        mockMvc.perform(post(messagesUrl(roomId)).with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "I am Alice",
                                "senderId", bob.id(), "senderUsername", "bob",
                                "roomId", Long.MAX_VALUE, "createdAt", "2000-01-01T00:00:00Z"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderId").value(alice.id()))
                .andExpect(jsonPath("$.senderUsername").value("alice"))
                .andExpect(jsonPath("$.roomId").value(roomId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_messages WHERE sender_id = ? AND room_id = ? AND created_at > '2020-01-01'",
                Long.class, alice.id(), roomId
        )).isEqualTo(1L);
    }

    @Test
    void joiningAllowsASecondAccountToReadAndSend() throws Exception {
        Long roomId = createRoom("General");
        send(roomId, "Hello Bob", "alice");
        membershipService.join(roomId, "bob");
        send(roomId, "Hello Alice", "bob");
        mockMvc.perform(get(messagesUrl(roomId)).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].senderUsername").value("bob"))
                .andExpect(jsonPath("$.items[1].senderUsername").value("alice"));
    }

    @Test
    void anEmptyRoomHasAnEmptyHistory() throws Exception {
        Long roomId = createRoom("General");
        mockMvc.perform(get(messagesUrl(roomId)).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextBefore").value(nullValue()));
    }

    @Test
    void historyUsesAnExclusiveCursorEvenWhenANewerMessageArrivesBetweenPages() throws Exception {
        Long roomId = createRoom("General");
        Long first = send(roomId, "First", "alice");
        Long second = send(roomId, "Second", "alice");
        Long third = send(roomId, "Third", "alice");
        mockMvc.perform(get(messagesUrl(roomId)).with(user("alice")).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(third))
                .andExpect(jsonPath("$.items[1].id").value(second))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextBefore").value(second));

        // A new message should not shift the older page: the cursor is an ID, not a row offset.
        send(roomId, "Fourth", "alice");
        mockMvc.perform(get(messagesUrl(roomId)).with(user("alice"))
                        .param("size", "2").param("before", second.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextBefore").value(nullValue()));
        mockMvc.perform(get(messagesUrl(roomId)).with(user("alice")).param("before", first.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void bothHistoryQueriesStayInsideTheRequestedRoom() throws Exception {
        Long general = createRoom("General");
        Long java = createRoom("Java");
        Long generalMessage = send(general, "Only in General", "alice");
        Long javaMessage = send(java, "Only in Java", "alice");
        mockMvc.perform(get(messagesUrl(general)).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(generalMessage));
        mockMvc.perform(get(messagesUrl(general)).with(user("alice"))
                        .param("before", javaMessage.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(generalMessage));
    }

    @Test
    void leavingBlocksFurtherAccessAndRejoiningRestoresTheSavedHistory() throws Exception {
        Long roomId = createRoom("General");
        membershipService.join(roomId, "bob");
        Long messageId = send(roomId, "Keep this history", "bob");
        mockMvc.perform(delete("/api/rooms/{id}/members/me", roomId).with(user("bob")).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(messagesUrl(roomId)).with(user("bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROOM_MEMBERSHIP_REQUIRED"));
        mockMvc.perform(post(messagesUrl(roomId)).with(user("bob")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Blocked\"}"))
                .andExpect(status().isForbidden());
        assertThat(messageCount()).isEqualTo(1L);

        membershipService.join(roomId, "bob");
        mockMvc.perform(get(messagesUrl(roomId)).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(messageId));
    }

    @Test
    void aRealLoginSessionAndFreshCsrfTokenCanSendAMessage() throws Exception {
        Long roomId = createRoom("General");
        Client client = csrfClient(null);
        mockMvc.perform(client.apply(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "alice").param("password", PASSWORD))
                .andExpect(status().isNoContent());
        // Login rotates the CSRF token; fetch the token for the authenticated session.
        Client signedIn = csrfClient(client.session());
        mockMvc.perform(signedIn.apply(post(messagesUrl(roomId)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"From my session\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderId").value(alice.id()));
        mockMvc.perform(get(messagesUrl(roomId)).session(signedIn.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].content").value("From my session"));
    }

    @Test
    void sendingRequiresCsrfEvenWhenAlreadySignedIn() throws Exception {
        Long roomId = createRoom("General");
        mockMvc.perform(post(messagesUrl(roomId)).with(user("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Blocked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF"));
        assertThat(messageCount()).isZero();
    }

    @Test
    void malformedJsonReturns400WithoutSavingAMessage() throws Exception {
        Long roomId = createRoom("General");
        mockMvc.perform(post(messagesUrl(roomId)).with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        assertThat(messageCount()).isZero();
    }

    @Test
    void messagesCanUseTheFullLengthLimit() throws Exception {
        Long roomId = createRoom("General");
        Long messageId = send(roomId, "x".repeat(2000), "alice");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT char_length(content) FROM chat_messages WHERE id = ?", Integer.class, messageId
        )).isEqualTo(2000);
    }

    @Test
    void defaultAndMaximumHistorySizesAreBounded() throws Exception {
        Long roomId = createRoom("General");
        // Seed a larger history directly; HTTP behavior is tested by the reads below.
        for (int i = 0; i < 52; i++) {
            jdbcTemplate.update(
                    "INSERT INTO chat_messages (room_id, sender_id, content, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    roomId, alice.id(), "Message " + i
            );
        }
        mockMvc.perform(get(messagesUrl(roomId)).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].content").value("Message 51"))
                .andExpect(jsonPath("$.hasMore").value(true));
        mockMvc.perform(get(messagesUrl(roomId)).with(user("alice")).param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void theDatabaseEnforcesReferencesAndContentLengthAndCascadesRoomDeletion() throws Exception {
        Long roomId = createRoom("General");
        for (String content : new String[]{"", "x".repeat(2001)}) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO chat_messages (room_id, sender_id, content, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    roomId, alice.id(), content
            )).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO chat_messages (room_id, sender_id, content, created_at) VALUES (?, ?, 'Invalid sender', CURRENT_TIMESTAMP)",
                roomId, Long.MAX_VALUE
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO chat_messages (room_id, sender_id, content, created_at) VALUES (?, ?, 'Invalid room', CURRENT_TIMESTAMP)",
                Long.MAX_VALUE, alice.id()
        )).isInstanceOf(DataIntegrityViolationException.class);
        send(roomId, "Valid message", "alice");
        jdbcTemplate.update("DELETE FROM chat_rooms WHERE id = ?", roomId);
        assertThat(messageCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidContent")
    void invalidContentReturnsAFieldError(String content) throws Exception {
        Long roomId = createRoom("General");
        mockMvc.perform(post(messagesUrl(roomId)).with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.content").exists());
        assertThat(messageCount()).isZero();
    }

    static Stream<String> invalidContent() {
        return Stream.of("", "   ", "\t\n", "x".repeat(2001));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"content\":null}"})
    void missingOrNullContentReturnsAFieldError(String body) throws Exception {
        Long roomId = createRoom("General");
        mockMvc.perform(post(messagesUrl(roomId)).with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.content").exists());
        assertThat(messageCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void aSignedInNonmemberCannotReadOrSend(String method) throws Exception {
        Long roomId = createRoom("General");
        mockMvc.perform(messageRequest(method, roomId).with(user("bob")).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROOM_MEMBERSHIP_REQUIRED"));
        assertThat(messageCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void missingRoomsReturn404(String method) throws Exception {
        mockMvc.perform(messageRequest(method, Long.MAX_VALUE).with(user("alice")).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
        assertThat(messageCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void anonymousRequestsRequireAuthentication(String method) throws Exception {
        Long roomId = createRoom("General");
        // A valid test CSRF token isolates the authentication check on POST.
        mockMvc.perform(messageRequest(method, roomId).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        assertThat(messageCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"size,0", "size,-1", "size,51", "size,invalid", "before,0", "before,-1", "before,invalid"})
    void invalidHistoryParametersReturn400(String parameter, String value) throws Exception {
        Long roomId = createRoom("General");
        mockMvc.perform(get(messagesUrl(roomId)).with(user("alice")).param(parameter, value))
                .andExpect(status().isBadRequest());
    }

    private Long createRoom(String name) throws Exception {
        var result = mockMvc.perform(post("/api/rooms").with(user("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    // with(user(...)) supplies a test identity; the account itself was saved in resetTestData.
    private Long send(Long roomId, String content, String username) throws Exception {
        var result = mockMvc.perform(post(messagesUrl(roomId)).with(user(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private MockHttpServletRequestBuilder messageRequest(String method, Long roomId) {
        return switch (method) {
            case "GET" -> get(messagesUrl(roomId));
            case "POST" -> post(messagesUrl(roomId)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"Hello\"}");
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
    }

    private String messagesUrl(Long roomId) {
        return "/api/rooms/" + roomId + "/messages";
    }

    private long messageCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM chat_messages", Long.class);
    }

    private Client csrfClient(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        var result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Client((MockHttpSession) result.getRequest().getSession(false),
                json.get("headerName").asText(), json.get("token").asText());
    }

    private record Client(MockHttpSession session, String headerName, String token) {
        MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request) {
            return request.session(session).header(headerName, token);
        }
    }
}
