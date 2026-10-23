package br.com.glicemio;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MealsApiTests {
    private static final String ISSUER = "https://test-project.supabase.co/auth/v1";
    private static final String AT = "2024-03-02T12:34:56.123Z";
    private final JsonMapper json = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsAndListsPrivateFoods() throws Exception {
        String user = uuid(), other = uuid(), id = uuid();
        create(user, "/foods", data("id", id, "name", "Synthetic food"));
        mvc.perform(as(get("/api/v1/foods"), user)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(as(get("/api/v1/foods"), other)).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void legacyMealNeedsNoStructuredItemsAndKeepsOriginalMetadata() throws Exception {
        String user = uuid(), id = uuid();
        Map<String, Object> meal = meal(id);
        meal.put("description", "Legacy free text, synthetic example");
        meal.put("createdAt", AT); meal.put("updatedAt", "2024-03-02T12:35:56.456Z");
        create(user, "/meals", meal);
        mvc.perform(as(get("/api/v1/meals/" + id), user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.at").value(AT))
                .andExpect(jsonPath("$.createdAt").value(AT)).andExpect(jsonPath("$.updatedAt").value(meal.get("updatedAt")))
                .andExpect(jsonPath("$.description").value(meal.get("description")));
        mvc.perform(as(get("/api/v1/meals/" + id + "/items"), user)).andExpect(content().json("[]"));
    }

    @Test
    void multipleDecimalItemsKeepHistoricalNamesAfterFoodRenameAndDeletion() throws Exception {
        String user = uuid(), meal = uuid(), food = uuid(), item = uuid();
        create(user, "/meals", meal(meal));
        create(user, "/foods", data("id", food, "name", "Original synthetic food"));
        String path = "/meals/" + meal + "/items";
        create(user, path, item(item, food, "Original synthetic food", "1.234567"));
        create(user, path, item(uuid(), null, "Free text food", "0.125"));
        putOk(user, "/foods/" + food, data("name", "Renamed synthetic food", "revision", 1));
        mvc.perform(as(delete("/api/v1/foods/" + food).param("revision", "2"), user)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1" + path), user)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(as(get("/api/v1" + path + "/" + item), user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.foodName").value("Original synthetic food")).andExpect(jsonPath("$.quantity").value(1.234567));
        Map<String, Object> update = item(item, food, "Original synthetic food", "2.5");
        update.remove("id"); update.put("revision", 1);
        putOk(user, path + "/" + item, update);
        mvc.perform(as(post("/api/v1" + path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(item(uuid(), food, "New item", "1"))), user)).andExpect(status().isNotFound());
    }

    @Test
    void linkedGlucosePreservesLegacyEpisodeAndMetadata() throws Exception {
        String user = uuid(), meal = uuid(), id = uuid(), episode = uuid();
        create(user, "/meals", meal(meal));
        Map<String, Object> reading = glucose(id, meal);
        reading.put("episodeId", episode); reading.put("createdAt", AT); reading.put("updatedAt", AT);
        create(user, "/glucose-readings", reading);
        mvc.perform(as(get("/api/v1/glucose-readings/" + id), user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mealId").value(meal)).andExpect(jsonPath("$.episodeId").value(episode))
                .andExpect(jsonPath("$.createdAt").value(AT)).andExpect(jsonPath("$.value").value(100.125));
        mvc.perform(as(get("/api/v1/glucose-readings").param("from", "2024-03-02T00:00:00Z").param("to", "2024-03-03T00:00:00Z"), user))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(as(get("/api/v1/glucose-readings").param("from", "2024-03-03T00:00:00Z"), user)).andExpect(content().json("[]"));
    }

    @Test
    void allResourcesRejectReadsUpdatesDeletesAndLinksAcrossOwners() throws Exception {
        String owner = uuid(), other = uuid(), meal = uuid(), food = uuid(), item = uuid(), glucose = uuid();
        create(owner, "/foods", data("id", food, "name", "Private synthetic food"));
        create(owner, "/meals", meal(meal));
        create(owner, "/meals/" + meal + "/items", item(item, food, "Private synthetic food", "1"));
        create(owner, "/glucose-readings", glucose(glucose, meal));
        for (String path : List.of("/foods/" + food, "/meals/" + meal, "/meals/" + meal + "/items/" + item, "/glucose-readings/" + glucose)) {
            mvc.perform(as(get("/api/v1" + path), other)).andExpect(status().isNotFound());
            mvc.perform(as(delete("/api/v1" + path).param("revision", "1"), other)).andExpect(status().isNotFound());
        }
        mvc.perform(as(put("/api/v1/foods/" + food).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Other\",\"revision\":1}"), other)).andExpect(status().isNotFound());
        Map<String, Object> updateMeal = meal(meal); updateMeal.remove("id"); updateMeal.put("revision", 1);
        rejectWrite(other, put("/api/v1/meals/" + meal), updateMeal, 404);
        Map<String, Object> updateItem = item(item, food, "Other", "1"); updateItem.remove("id"); updateItem.put("revision", 1);
        rejectWrite(other, put("/api/v1/meals/" + meal + "/items/" + item), updateItem, 404);
        Map<String, Object> updateGlucose = glucose(glucose, meal); updateGlucose.remove("id"); updateGlucose.put("revision", 1);
        rejectWrite(other, put("/api/v1/glucose-readings/" + glucose), updateGlucose, 404);
        rejectWrite(other, post("/api/v1/glucose-readings"), glucose(uuid(), meal), 404);
        rejectWrite(other, post("/api/v1/meals/" + meal + "/items"), item(uuid(), null, "Other", "1"), 404);
        String ownMeal = uuid(); create(other, "/meals", meal(ownMeal));
        rejectWrite(other, post("/api/v1/meals/" + ownMeal + "/items"), item(uuid(), food, "Other", "1"), 404);
        rejectWrite(other, post("/api/v1/foods"), data("id", food, "name", "Collision"), 409);
    }

    @Test
    void allResourceRoutesRequireJwt() throws Exception {
        for (String path : List.of("/foods", "/meals", "/meals/" + uuid() + "/items", "/glucose-readings")) {
            mvc.perform(get("/api/v1" + path)).andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
            mvc.perform(post("/api/v1" + path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void uuidCreateIsIdempotentAndDifferentPayloadConflicts() throws Exception {
        String user = uuid(), id = uuid();
        var payload = data("id", id, "name", "Synthetic food");
        JsonNode first = create(user, "/foods", payload);
        mvc.perform(as(post("/api/v1/foods").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), user))
                .andExpect(status().isOk()).andExpect(content().json(first.toString()));
        rejectWrite(user, post("/api/v1/foods"), data("id", id, "name", "Different"), 409);
        mvc.perform(as(delete("/api/v1/foods/" + id).param("revision", "1"), user)).andExpect(status().isNoContent());
        rejectWrite(user, post("/api/v1/foods"), payload, 409);
    }

    @Test
    void optimisticRevisionPreventsLostUpdatesAndStaleDeletion() throws Exception {
        String user = uuid(), id = uuid();
        create(user, "/foods", data("id", id, "name", "Synthetic food"));
        putOk(user, "/foods/" + id, data("name", "Updated", "revision", 1));
        rejectWrite(user, put("/api/v1/foods/" + id), data("name", "Stale", "revision", 1), 409);
        rejectWrite(user, put("/api/v1/foods/" + id), data("name", "No revision"), 400);
        mvc.perform(as(delete("/api/v1/foods/" + id).param("revision", "1"), user)).andExpect(status().isConflict());
        mvc.perform(as(get("/api/v1/foods/" + id), user)).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.name").value("Updated"));
    }

    @Test
    void deletionNeverCascadesAndMealRequiresBothReferencesToBeInactive() throws Exception {
        String user = uuid(), meal = uuid(), item = uuid(), reading = uuid();
        create(user, "/meals", meal(meal));
        create(user, "/meals/" + meal + "/items", item(item, null, "Synthetic", "0.5"));
        create(user, "/glucose-readings", glucose(reading, meal));
        mvc.perform(as(delete("/api/v1/meals/" + meal).param("revision", "1"), user)).andExpect(status().isConflict());
        mvc.perform(as(delete("/api/v1/meals/" + meal + "/items/" + item).param("revision", "1"), user)).andExpect(status().isNoContent());
        mvc.perform(as(delete("/api/v1/meals/" + meal).param("revision", "1"), user)).andExpect(status().isConflict());
        mvc.perform(as(delete("/api/v1/glucose-readings/" + reading).param("revision", "1"), user)).andExpect(status().isNoContent());
        mvc.perform(as(delete("/api/v1/meals/" + meal).param("revision", "1"), user)).andExpect(status().isNoContent());
        for (String table : List.of("meal", "meal_item", "glucose_reading")) {
            String id = table.equals("meal") ? meal : table.equals("meal_item") ? item : reading;
            assertThat(jdbc.queryForObject("SELECT revision FROM " + table + " WHERE id = ? AND deleted_at IS NOT NULL", Long.class, UUID.fromString(id))).isEqualTo(2L);
        }
        mvc.perform(as(get("/api/v1/meals/" + meal), user)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/glucose-readings"), user)).andExpect(content().json("[]"));
    }

    @Test
    void invalidDecimalsUnitsTimestampsAndOwnershipFieldsAreRejectedWithoutEcho() throws Exception {
        String user = uuid(), meal = uuid(); create(user, "/meals", meal(meal));
        for (String invalid : List.of("0", "-1", "0.0000001", "1000000000000")) {
            rejectWrite(user, post("/api/v1/meals/" + meal + "/items"), item(uuid(), null, "Sensitive synthetic text", invalid), 400);
            var glucose = glucose(uuid(), null); glucose.put("value", new BigDecimal(invalid));
            rejectWrite(user, post("/api/v1/glucose-readings"), glucose, 400);
        }
        var invalidUnit = item(uuid(), null, "Synthetic", "1"); invalidUnit.put("unit", "spoon");
        rejectWrite(user, post("/api/v1/meals/" + meal + "/items"), invalidUnit, 400);
        for (String time : List.of("2024-03-02T12:34:56", "2024-03-02T12:34:56.1234567Z")) {
            var invalid = meal(uuid()); invalid.put("at", time); rejectWrite(user, post("/api/v1/meals"), invalid, 400);
        }
        var ownerInput = meal(uuid()); ownerInput.put("userId", uuid());
        rejectWrite(user, post("/api/v1/meals"), ownerInput, 400);
        var dates = meal(uuid()); dates.put("createdAt", AT); dates.put("updatedAt", "2020-01-01T00:00:00Z");
        rejectWrite(user, post("/api/v1/meals"), dates, 400);
    }

    @Test
    void nestedItemCannotBeAccessedThroughWrongMeal() throws Exception {
        String user = uuid(), first = uuid(), second = uuid(), item = uuid();
        create(user, "/meals", meal(first)); create(user, "/meals", meal(second));
        var payload = item(item, null, "Synthetic", "1"); create(user, "/meals/" + first + "/items", payload);
        mvc.perform(as(get("/api/v1/meals/" + second + "/items/" + item), user)).andExpect(status().isNotFound());
        rejectWrite(user, post("/api/v1/meals/" + second + "/items"), payload, 404);
    }

    @Test
    void databaseIndependentlyEnforcesOwnerAndDecimalConstraints() throws Exception {
        String a = uuid(), b = uuid(), meal = uuid(), food = uuid();
        create(a, "/meals", meal(meal)); create(b, "/foods", data("id", food, "name", "Synthetic"));
        UUID ownerA = owner(a), ownerB = owner(b);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO glucose_reading(id,user_id,value,at,meal_id,created_at,updated_at) VALUES (?,?,100,now(),?,now(),now())",
                UUID.randomUUID(), ownerB, UUID.fromString(meal))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO meal_item(id,user_id,meal_id,food_id,food_name,quantity,unit,created_at,updated_at) VALUES (?,?,?,?,'Synthetic',1,'g',now(),now())",
                UUID.randomUUID(), ownerA, UUID.fromString(meal), UUID.fromString(food))).isInstanceOf(DataIntegrityViolationException.class);
        for (String value : List.of("0", "-1", "0.1234567", "1000000000000", "NaN", "Infinity")) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO glucose_reading(id,user_id,value,at,created_at,updated_at) VALUES (?,?,?::numeric,now(),now(),now())",
                    UUID.randomUUID(), ownerA, value)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE meal SET revision=0 WHERE id=?", UUID.fromString(meal))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM app_user WHERE id=?", ownerA)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(1);
    }

    @Test
    void identityIncludesIssuerAndConcurrentProvisioningIsIdempotent() throws Exception {
        String user = uuid(), id = uuid(); var payload = data("id", id, "name", "Concurrent synthetic food");
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> request = () -> { start.await(); return mvc.perform(as(post("/api/v1/foods")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), user)).andReturn().getResponse().getStatus(); };
            Future<Integer> one = pool.submit(request), two = pool.submit(request); start.countDown();
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 201);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE issuer=? AND subject=?", Integer.class, ISSUER, user)).isEqualTo(1);
        // jwt() bypasses cryptographic validation only for the domain tests; decoder tests cover actual signatures.
        mvc.perform(get("/api/v1/foods").with(jwt().jwt(builder -> builder.subject(user).issuer("https://second-issuer.example"))))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE subject=?", Integer.class, user)).isEqualTo(2);
    }

    @Test
    void mealItemAndGlucoseCreationReplaysPreserveIdsAndRevisions() throws Exception {
        String user = uuid(), meal = uuid();
        Map<String, Map<String, Object>> requests = new LinkedHashMap<>();
        requests.put("/meals", meal(meal));
        requests.put("/meals/" + meal + "/items", item(uuid(), null, "Synthetic", "1.25"));
        requests.put("/glucose-readings", glucose(uuid(), meal));
        for (var entry : requests.entrySet()) {
            JsonNode first = create(user, entry.getKey(), entry.getValue());
            mvc.perform(as(post("/api/v1" + entry.getKey()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(entry.getValue())), user))
                    .andExpect(status().isOk()).andExpect(content().json(first.toString()));
        }
    }

    @Test
    void clinicalResourcesUpdateWithRevisionAndKeepCreationTime() throws Exception {
        String user = uuid(), meal = uuid(), item = uuid(), reading = uuid();
        var original = meal(meal); original.put("createdAt", AT); original.put("updatedAt", AT);
        create(user, "/meals", original);
        var changed = meal(meal); changed.remove("id"); changed.put("description", "Edited synthetic legacy text"); changed.put("revision", 1);
        putOk(user, "/meals/" + meal, changed);
        rejectWrite(user, put("/api/v1/meals/" + meal), changed, 409);
        mvc.perform(as(get("/api/v1/meals/" + meal), user)).andExpect(jsonPath("$.createdAt").value(AT)).andExpect(jsonPath("$.revision").value(2));
        String itemPath = "/meals/" + meal + "/items";
        create(user, itemPath, item(item, null, "Synthetic", "1"));
        var itemUpdate = item(item, null, "Edited synthetic", "2.123456"); itemUpdate.remove("id"); itemUpdate.put("revision", 1);
        putOk(user, itemPath + "/" + item, itemUpdate);
        rejectWrite(user, put("/api/v1" + itemPath + "/" + item), itemUpdate, 409);
        create(user, "/glucose-readings", glucose(reading, null));
        var glucoseUpdate = glucose(reading, meal); glucoseUpdate.remove("id"); glucoseUpdate.put("revision", 1);
        putOk(user, "/glucose-readings/" + reading, glucoseUpdate);
        rejectWrite(user, put("/api/v1/glucose-readings/" + reading), glucoseUpdate, 409);
        String other = uuid(), otherMeal = uuid(); create(other, "/meals", meal(otherMeal));
        glucoseUpdate.put("revision", 2); glucoseUpdate.put("mealId", otherMeal);
        rejectWrite(user, put("/api/v1/glucose-readings/" + reading), glucoseUpdate, 404);
        mvc.perform(as(get("/api/v1/glucose-readings/" + reading), user)).andExpect(jsonPath("$.mealId").value(meal)).andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void concurrentMealDeletionAndGlucoseLinkCannotLeaveActiveOrphan() throws Exception {
        String user = uuid(), meal = uuid(); create(user, "/meals", meal(meal));
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> deletion = pool.submit(() -> { start.await(); return mvc.perform(as(delete("/api/v1/meals/" + meal).param("revision", "1"), user))
                    .andReturn().getResponse().getStatus(); });
            Future<Integer> creation = pool.submit(() -> { start.await(); return mvc.perform(as(post("/api/v1/glucose-readings")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(glucose(uuid(), meal))), user))
                    .andReturn().getResponse().getStatus(); });
            start.countDown(); int deleted = deletion.get(20, TimeUnit.SECONDS), created = creation.get(20, TimeUnit.SECONDS);
            assertThat((deleted == 204 && created == 404) || (deleted == 409 && created == 201)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM glucose_reading g JOIN meal m ON g.user_id=m.user_id AND g.meal_id=m.id WHERE g.deleted_at IS NULL AND m.deleted_at IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void databaseRejectsCrossOwnerMealItemsInvalidQuantitiesAndPhysicalMealDeletion() throws Exception {
        String user = uuid(), other = uuid(), meal = uuid();
        create(user, "/meals", meal(meal)); create(other, "/foods", data("id", uuid(), "name", "Synthetic"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO meal_item(id,user_id,meal_id,food_name,quantity,unit,created_at,updated_at) VALUES (?,?,?,'Synthetic',1,'g',now(),now())",
                UUID.randomUUID(), owner(other), UUID.fromString(meal))).isInstanceOf(DataIntegrityViolationException.class);
        for (String quantity : List.of("0", "-1", "1.0000001", "1000000000000")) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO meal_item(id,user_id,meal_id,food_name,quantity,unit,created_at,updated_at) VALUES (?,?,?,'Synthetic',?::numeric,'g',now(),now())",
                    UUID.randomUUID(), owner(user), UUID.fromString(meal), quantity)).isInstanceOf(DataIntegrityViolationException.class);
        }
        create(user, "/glucose-readings", glucose(uuid(), meal));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM meal WHERE id=?", UUID.fromString(meal))).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID owner(String subject) { return jdbc.queryForObject("SELECT id FROM app_user WHERE issuer=? AND subject=?", UUID.class, ISSUER, subject); }
    private String uuid() { return UUID.randomUUID().toString(); }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String user) {
        return request.with(jwt().jwt(builder -> builder.subject(user).issuer(ISSUER)));
    }
    private JsonNode create(String user, String path, Map<String, Object> payload) throws Exception {
        String body = mvc.perform(as(post("/api/v1" + path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), user))
                .andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
    private void putOk(String user, String path, Map<String, Object> payload) throws Exception {
        mvc.perform(as(put("/api/v1" + path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), user)).andExpect(status().isOk());
    }
    private void rejectWrite(String user, MockHttpServletRequestBuilder request, Map<String, Object> payload, int status) throws Exception {
        String body = mvc.perform(as(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)), user))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Sensitive synthetic text", "SELECT ", "INSERT ", "org.postgresql", "rejectedValue");
    }
    private Map<String, Object> meal(String id) { return data("id", id, "kind", "lunch", "at", AT); }
    private Map<String, Object> item(String id, String food, String name, String quantity) {
        return data("id", id, "foodId", food, "foodName", name, "quantity", new BigDecimal(quantity), "unit", "g");
    }
    private Map<String, Object> glucose(String id, String meal) {
        return data("id", id, "value", new BigDecimal("100.125"), "at", AT, "context", "synthetic", "note", "Synthetic test only", "mealId", meal);
    }
    private Map<String, Object> data(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) { result.put((String) pairs[i], pairs[i + 1]); }
        return result;
    }
}
