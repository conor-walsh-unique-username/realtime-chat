package com.example.chat.auth;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

// Load the real app and security filters; MockMvc sends requests without starting a browser.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthSessionIT {

    private static final String PASSWORD = "StudyChat2026!";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("chat_session_test")
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

    @BeforeEach
    void resetTestData() {
        // Only the disposable database selected by DynamicPropertySource is touched.
        jdbcTemplate.update("DELETE FROM chat_rooms");
        jdbcTemplate.update("DELETE FROM chat_users");
        registrationService.register(new RegisterRequest("alice", PASSWORD));
    }

    @Test
    void anonymousUsersCanReadHealthButCannotReadAccountsOrRooms() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(get("/api/rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void registrationRequiresAndAcceptsTheTokenFromTheCsrfEndpoint() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "bob", "password", PASSWORD));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_users WHERE username = 'bob'", Long.class
        )).isZero();

        Client client = csrf(null);
        mockMvc.perform(client.apply(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("bob"));

        // Registration creates an account; the client still needs to log in.
        mockMvc.perform(get("/api/auth/me").session(client.session()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginVerifiesTheStoredHashChangesTheSessionIdAndPersistsAuthentication() throws Exception {
        Client client = csrf(null);
        String anonymousSessionId = client.session().getId();
        mockMvc.perform(client.apply(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "ALICE").param("password", PASSWORD))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Location"));

        assertThat(client.session().getId()).isNotEqualTo(anonymousSessionId);
        mockMvc.perform(get("/api/auth/me").session(client.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mockMvc.perform(get("/api/rooms").session(client.session()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "does_not_exist"})
    void wrongPasswordsAndUnknownAccountsGetTheSamePublicError(String username) throws Exception {
        Client client = csrf(null);
        mockMvc.perform(client.apply(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username).param("password", "incorrect-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Incorrect username or password."));
        mockMvc.perform(get("/api/auth/me").session(client.session()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithoutCsrfIsRejectedEvenWhenCredentialsAreCorrect() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "alice").param("password", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF"));
    }

    @Test
    void signedInUsersStillNeedCsrfForRoomCreation() throws Exception {
        Client client = login("alice");
        mockMvc.perform(post("/api/rooms").session(client.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"General\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_rooms", Long.class)).isZero();
    }

    @Test
    void loginClearsTheOldCsrfTokenAndANewTokenPermitsRoomCreation() throws Exception {
        Client beforeLogin = login("alice");
        mockMvc.perform(beforeLogin.apply(post("/api/rooms"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"General\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF"));

        Client afterLogin = csrf(beforeLogin.session());
        mockMvc.perform(afterLogin.apply(post("/api/rooms"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"General\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void logoutInvalidatesTheSessionAndExpiresTheCookie() throws Exception {
        Client client = csrf(login("alice").session());
        var result = mockMvc.perform(client.apply(post("/api/auth/logout")))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(client.session().isInvalid()).isTrue();
        assertThat(result.getResponse().getCookie("CHATSESSION")).isNotNull();
        assertThat(result.getResponse().getCookie("CHATSESSION").getMaxAge()).isZero();
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutCsrfFailsAndLeavesTheAccountSignedIn() throws Exception {
        Client client = login("alice");
        mockMvc.perform(post("/api/auth/logout").session(client.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF"));
        mockMvc.perform(get("/api/auth/me").session(client.session()))
                .andExpect(status().isOk());
    }

    @Test
    void twoSessionsKeepTheirIdentitiesAndLoggingOutOneDoesNotLogOutTheOther() throws Exception {
        registrationService.register(new RegisterRequest("bob", PASSWORD));
        Client alice = csrf(login("alice").session());
        Client bob = login("bob");

        mockMvc.perform(get("/api/auth/me").session(alice.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
        mockMvc.perform(get("/api/auth/me").session(bob.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"));
        mockMvc.perform(alice.apply(post("/api/auth/logout")))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").session(bob.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"));
    }

    @Test
    void allowedFrontendCanPreflightCredentialedRequests() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void anUnknownOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://unknown.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // Carry the session and its token together, like a browser with one cookie jar.
    private Client csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        var result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Client((MockHttpSession) result.getRequest().getSession(false),
                json.get("headerName").asText(), json.get("token").asText());
    }

    // Returns the pre-login token deliberately; fetch a new one before the next POST.
    private Client login(String username) throws Exception {
        Client client = csrf(null);
        mockMvc.perform(client.apply(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username).param("password", PASSWORD))
                .andExpect(status().isNoContent());
        return client;
    }

    private record Client(MockHttpSession session, String headerName, String token) {
        MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request) {
            return request.session(session).header(headerName, token);
        }
    }
}
