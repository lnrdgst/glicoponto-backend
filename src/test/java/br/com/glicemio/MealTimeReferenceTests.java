package br.com.glicemio;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "debug=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MealTimeReferenceTests {
    private static final String ISSUER = "https://test-project.supabase.co/auth/v1";
    private static final String AT = "2024-03-02T12:34:56.123Z";
    private static final String UPDATED = "2024-03-02T12:35:56.456Z";
    private final JsonMapper json = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsMealWithStartReference() throws Exception {
        assertNewReference("meal_start");
    }

    @Test
    void createsMealWithEndReference() throws Exception {
        assertNewReference("meal_end");
    }

    @Test
    void legacyPayloadDefaultsToUnknownWithoutInferringFromDescription() throws Exception {
        String user = uuid(), id = uuid();
        var payload = meal(id);
        payload.put("description", "Synthetic legacy text: finished eating");
        JsonNode result = create(user, payload);
        assertThat(result.get("timeReference").asString()).isEqualTo("unknown");
        assertThat(result.get("at").asString()).isEqualTo(AT);
        assertThat(result.get("createdAt").asString()).isEqualTo(AT);
        assertThat(result.get("updatedAt").asString()).isEqualTo(UPDATED);
        mvc.perform(as(get("/api/v1/meals/" + id), user)).andExpect(status().isOk()).andExpect(jsonPath("$.timeReference").value("unknown"));
    }

    @Test
    void omittedReferenceOnUpdatePreservesExistingReference() throws Exception {
        for (String reference : List.of("meal_start", "meal_end", "unknown")) {
            String user = uuid(), id = uuid(); var payload = meal(id); payload.put("timeReference", reference);
            create(user, payload);
            var update = update(1); update.put("description", "Edited synthetic description");
            JsonNode result = putMeal(user, id, update);
            assertThat(result.get("timeReference").asString()).isEqualTo(reference);
            assertThat(result.get("at").asString()).isEqualTo(AT);
            assertThat(result.get("revision").asLong()).isEqualTo(2);
        }
    }

    @Test
    void explicitlyReclassifiesUnknownToEndWithOptimisticRevision() throws Exception {
        String user = uuid(), id = uuid(); create(user, meal(id));
        var update = update(1); update.put("timeReference", "meal_end");
        JsonNode result = putMeal(user, id, update);
        assertThat(result.get("timeReference").asString()).isEqualTo("meal_end");
        assertThat(result.get("revision").asLong()).isEqualTo(2);
        update.put("timeReference", "meal_start");
        reject(user, put("/api/v1/meals/" + id), update, 409);
        mvc.perform(as(get("/api/v1/meals/" + id), user)).andExpect(jsonPath("$.timeReference").value("meal_end")).andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void rejectsInvalidValuesAndExplicitNullOnCreateAndUpdate() throws Exception {
        String user = uuid(), id = uuid(); create(user, meal(id));
        for (Object invalid : Arrays.asList("invalid synthetic reference", "MEAL_START", "", " meal_start", null, 0, true, List.of("meal_start"))) {
            var payload = meal(uuid()); payload.put("timeReference", invalid);
            reject(user, post("/api/v1/meals"), payload, 400);
            var update = update(1); update.put("timeReference", invalid);
            reject(user, put("/api/v1/meals/" + id), update, 400);
        }
        mvc.perform(as(get("/api/v1/meals/" + id), user)).andExpect(jsonPath("$.timeReference").value("unknown")).andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void changingOnlyReferencePreservesMealInstantAndCreationMetadata() throws Exception {
        String user = uuid(), id = uuid(); JsonNode original = create(user, meal(id));
        var update = update(1); update.put("timeReference", "meal_start");
        JsonNode result = putMeal(user, id, update);
        for (String field : List.of("id", "kind", "at", "description", "createdAt")) {
            assertThat(result.get(field)).as(field).isEqualTo(original.get(field));
        }
        assertThat(result.get("updatedAt")).isNotEqualTo(original.get("updatedAt"));
        assertThat(jdbc.queryForObject("SELECT at FROM meal WHERE id=?", java.sql.Timestamp.class, UUID.fromString(id)).toInstant().toString()).isEqualTo(AT);
    }

    @Test
    void glucoseAssociationAndItsRevisionAreUnchangedAfterMealReclassification() throws Exception {
        String user = uuid(), id = uuid(), reading = uuid(); create(user, meal(id));
        var glucose = data("id", reading, "value", 100, "at", AT, "mealId", id, "createdAt", AT, "updatedAt", UPDATED);
        String before = mvc.perform(as(post("/api/v1/glucose-readings").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(glucose)), user))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var update = update(1); update.put("timeReference", "meal_end"); putMeal(user, id, update);
        mvc.perform(as(get("/api/v1/glucose-readings/" + reading), user)).andExpect(status().isOk()).andExpect(content().json(before));
    }

    @Test
    void referenceCannotBeChangedByAnotherOwner() throws Exception {
        String user = uuid(), other = uuid(), id = uuid(); JsonNode original = create(user, meal(id));
        var update = update(1); update.put("timeReference", "meal_end");
        reject(other, put("/api/v1/meals/" + id), update, 404);
        mvc.perform(as(get("/api/v1/meals/" + id), user)).andExpect(status().isOk()).andExpect(content().json(original.toString()));
    }

    @Test
    void databaseDefaultsToUnknownAndRejectsInvalidOrNullReferences() throws Exception {
        String user = uuid(), id = uuid(); create(user, meal(id));
        UUID owner = jdbc.queryForObject("SELECT user_id FROM meal WHERE id=?", UUID.class, UUID.fromString(id));
        UUID directId = UUID.randomUUID();
        jdbc.update("INSERT INTO meal(id,user_id,kind,at,created_at,updated_at) VALUES (?,?,'lunch',?::timestamptz,?::timestamptz,?::timestamptz)", directId, owner, AT, AT, UPDATED);
        assertThat(jdbc.queryForObject("SELECT time_reference FROM meal WHERE id=?", String.class, directId)).isEqualTo("unknown");
        for (String reference : Arrays.asList("invalid", "MEAL_START", null)) {
            assertThatThrownBy(() -> jdbc.update("UPDATE meal SET time_reference=? WHERE id=?", reference, directId)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void uuidIdempotenceIncludesTimeReferenceAndLegacyDefault() throws Exception {
        String user = uuid();
        for (String reference : Arrays.asList("meal_start", "meal_end", null)) {
            var payload = meal(uuid()); if (reference != null) { payload.put("timeReference", reference); }
            JsonNode original = create(user, payload);
            mvc.perform(as(post("/api/v1/meals").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), user))
                    .andExpect(status().isOk()).andExpect(content().json(original.toString()));
            payload.put("timeReference", "meal_end".equals(reference) ? "meal_start" : "meal_end");
            reject(user, post("/api/v1/meals"), payload, 409);
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void debugModeDoesNotLogClinicalRequestResponseOrInvalidValues(CapturedOutput output) throws Exception {
        String user = uuid(), id = uuid();
        var payload = meal(id); payload.put("kind", "synthetic-log-marker-kind");
        payload.put("description", "synthetic-log-marker-description");
        create(user, payload);
        mvc.perform(as(get("/api/v1/meals/" + id), user)).andExpect(status().isOk());
        var invalid = meal(uuid()); invalid.put("timeReference", "synthetic-log-marker-invalid");
        reject(user, post("/api/v1/meals"), invalid, 400);
        var invalidMeal = meal(uuid()); invalidMeal.put("kind", "");
        invalidMeal.put("description", "synthetic-log-marker-validation");
        reject(user, post("/api/v1/meals"), invalidMeal, 400);
        assertThat(output.getAll()).doesNotContain("synthetic-log-marker");
    }

    private void assertNewReference(String reference) throws Exception {
        String user = uuid(), id = uuid(); var payload = meal(id); payload.put("timeReference", reference);
        JsonNode result = create(user, payload);
        assertThat(result.get("timeReference").asString()).isEqualTo(reference);
        mvc.perform(as(get("/api/v1/meals/" + id), user)).andExpect(status().isOk()).andExpect(jsonPath("$.timeReference").value(reference));
        mvc.perform(as(get("/api/v1/meals"), user)).andExpect(status().isOk()).andExpect(jsonPath("$[0].timeReference").value(reference));
    }
    private JsonNode create(String user, Map<String, Object> payload) throws Exception {
        return json.readTree(mvc.perform(as(post("/api/v1/meals").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), user))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode putMeal(String user, String id, Map<String, Object> payload) throws Exception {
        return json.readTree(mvc.perform(as(put("/api/v1/meals/" + id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), user))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private void reject(String user, MockHttpServletRequestBuilder request, Map<String, Object> payload, int expected) throws Exception {
        String response = mvc.perform(as(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), user))
                .andExpect(status().is(expected)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("invalid synthetic reference", "Synthetic legacy meal", "SELECT ", "INSERT ", "rejectedValue");
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String user) {
        return request.with(jwt().jwt(builder -> builder.subject(user).issuer(ISSUER)));
    }
    private String uuid() { return UUID.randomUUID().toString(); }
    private Map<String, Object> meal(String id) {
        return data("id", id, "kind", "lunch", "at", AT, "description", "Synthetic legacy meal", "createdAt", AT, "updatedAt", UPDATED);
    }
    private Map<String, Object> update(long revision) {
        return data("kind", "lunch", "at", AT, "description", "Synthetic legacy meal", "revision", revision);
    }
    private Map<String, Object> data(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) { result.put((String) pairs[i], pairs[i + 1]); }
        return result;
    }
}
