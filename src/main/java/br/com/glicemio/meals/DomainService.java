package br.com.glicemio.meals;

import static br.com.glicemio.meals.DomainRepository.Resource.*;
import static br.com.glicemio.meals.DomainRepository.instant;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.glicemio.meals.Contracts.*;
import br.com.glicemio.meals.DomainRepository.Resource;

@Service
@Transactional
public class DomainService {
    private final DomainRepository repository;
    public DomainService(DomainRepository repository) { this.repository = repository; }

    public Created<Object> create(Jwt jwt, Resource resource, UUID mealId, UUID id,
            Map<String, Object> data, Instant createdAt, Instant updatedAt) {
        UUID owner = owner(jwt);
        validateTime(createdAt); validateTime(updatedAt);
        if (resource == ITEM) { data.put("meal_id", mealId); }
        Map<String, Object> existing = repository.find(resource, owner, id, true);
        if (existing != null) {
            if (resource == ITEM && !Objects.equals(existing.get("meal_id"), mealId)) { throw DomainException.notFound(); }
            if (existing.get("deleted_at") != null || !matches(existing, data)
                    || (createdAt != null && !createdAt.equals(instant(existing, "created_at")))
                    || (updatedAt != null && !updatedAt.equals(instant(existing, "updated_at")))) {
                throw DomainException.conflict();
            }
            return new Created<>(response(resource, existing), false);
        }
        validateReferences(resource, owner, data, null);
        Instant now = now();
        Instant created = createdAt == null ? now : createdAt;
        Instant updated = updatedAt == null ? created : updatedAt;
        if (updated.isBefore(created)) { throw DomainException.invalid(); }
        data.put("id", id); data.put("user_id", owner);
        data.put("created_at", Timestamp.from(created)); data.put("updated_at", Timestamp.from(updated));
        repository.insert(resource, data);
        return new Created<>(response(resource, required(resource, owner, id, mealId)), true);
    }

    public Object get(Jwt jwt, Resource resource, UUID mealId, UUID id) {
        return response(resource, required(resource, owner(jwt), id, mealId));
    }

    public List<Object> list(Jwt jwt, Resource resource, UUID mealId, Instant from, Instant to, int limit, int offset) {
        if (limit < 1 || limit > 200 || offset < 0 || (from != null && to != null && !from.isBefore(to))) {
            throw DomainException.invalid();
        }
        validateTime(from); validateTime(to);
        UUID owner = owner(jwt);
        if (resource == ITEM) { required(MEAL, owner, mealId, null); }
        return repository.list(resource, owner, mealId, from, to, limit, offset).stream()
                .map(row -> response(resource, row)).toList();
    }

    public Object update(Jwt jwt, Resource resource, UUID mealId, UUID id, long revision, Map<String, Object> data) {
        UUID owner = owner(jwt);
        var existing = required(resource, owner, id, mealId);
        checkRevision(existing, revision);
        if (resource == ITEM) { data.put("meal_id", mealId); }
        validateReferences(resource, owner, data, existing);
        data.put("updated_at", Timestamp.from(nextTime(existing)));
        repository.update(resource, owner, id, revision, data);
        return response(resource, required(resource, owner, id, mealId));
    }

    public void delete(Jwt jwt, Resource resource, UUID mealId, UUID id, long revision) {
        UUID owner = owner(jwt);
        var existing = required(resource, owner, id, mealId);
        checkRevision(existing, revision);
        if (resource == MEAL && repository.hasActiveReferences(owner, id)) { throw DomainException.conflict(); }
        Timestamp time = Timestamp.from(nextTime(existing));
        repository.update(resource, owner, id, revision, fields("updated_at", time, "deleted_at", time));
    }

    private UUID owner(Jwt jwt) {
        return repository.owner(jwt.getClaimAsString("iss"), jwt.getSubject());
    }

    private Map<String, Object> required(Resource resource, UUID owner, UUID id, UUID mealId) {
        var row = repository.find(resource, owner, id, false);
        if (row == null || (resource == ITEM && !Objects.equals(row.get("meal_id"), mealId))) { throw DomainException.notFound(); }
        return row;
    }

    private void validateReferences(Resource resource, UUID owner, Map<String, Object> data, Map<String, Object> existing) {
        if ((resource == ITEM || resource == GLUCOSE) && data.get("meal_id") != null) {
            required(MEAL, owner, (UUID) data.get("meal_id"), null);
        }
        if (resource == ITEM && data.get("food_id") != null) {
            // An already-linked deleted food can remain attached to its historical item.
            if (existing == null || !Objects.equals(existing.get("food_id"), data.get("food_id"))) {
                required(FOOD, owner, (UUID) data.get("food_id"), null);
            }
        }
    }

    private void checkRevision(Map<String, Object> row, long revision) {
        if (((Number) row.get("revision")).longValue() != revision) { throw DomainException.conflict(); }
    }

    private boolean matches(Map<String, Object> row, Map<String, Object> data) {
        return data.entrySet().stream().allMatch(entry -> {
            Object left = row.get(entry.getKey()); Object right = entry.getValue();
            if (left instanceof BigDecimal a && right instanceof BigDecimal b) { return a.compareTo(b) == 0; }
            return Objects.equals(left, right);
        });
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static Instant nextTime(Map<String, Object> row) {
        Instant now = now(); Instant previous = instant(row, "updated_at");
        return now.isAfter(previous) ? now : previous.plus(1, ChronoUnit.MICROS);
    }
    private static void validateTime(Instant time) {
        if (time != null && (time.getNano() % 1000 != 0 || time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || time.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")))) { throw DomainException.invalid(); }
    }

    static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof Instant time) { validateTime(time); value = Timestamp.from(time); }
            if (value instanceof Unit unit) { value = unit.name(); }
            if (value instanceof TimeReference reference) { value = reference.name(); }
            data.put((String) pairs[i], value);
        }
        return data;
    }

    private Object response(Resource resource, Map<String, Object> row) {
        UUID id = (UUID) row.get("id");
        Instant created = instant(row, "created_at"), updated = instant(row, "updated_at"), deleted = instant(row, "deleted_at");
        long revision = ((Number) row.get("revision")).longValue();
        return switch (resource) {
            case FOOD -> new Food(id, (String) row.get("name"), created, updated, deleted, revision);
            case MEAL -> new Meal(id, (String) row.get("kind"), instant(row, "at"), (String) row.get("description"), created, updated, deleted, revision,
                    TimeReference.valueOf((String) row.get("time_reference")));
            case ITEM -> new Item(id, (UUID) row.get("meal_id"), (UUID) row.get("food_id"), (String) row.get("food_name"),
                    (BigDecimal) row.get("quantity"), Unit.valueOf((String) row.get("unit")), created, updated, deleted, revision);
            case GLUCOSE -> new Glucose(id, (BigDecimal) row.get("value"), instant(row, "at"), (String) row.get("context"),
                    (String) row.get("note"), (UUID) row.get("meal_id"), (UUID) row.get("episode_id"), created, updated, deleted, revision);
        };
    }
}
