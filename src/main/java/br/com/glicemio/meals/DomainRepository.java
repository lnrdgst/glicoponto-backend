package br.com.glicemio.meals;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL identifiers come exclusively from Resource, never from HTTP input. */
@Repository
public class DomainRepository {
    enum Resource {
        FOOD("food", "created_at"), MEAL("meal", "at"), ITEM("meal_item", "created_at"), GLUCOSE("glucose_reading", "at");
        final String table;
        final String time;
        Resource(String table, String time) { this.table = table; this.time = time; }
    }

    private final JdbcTemplate jdbc;
    public DomainRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    UUID owner(String issuer, String subject) {
        jdbc.update("INSERT INTO app_user(id, issuer, subject) VALUES (?, ?, ?) ON CONFLICT (issuer, subject) DO NOTHING",
                UUID.randomUUID(), issuer, subject);
        // Serialize a user's writes, including reference checks and meal deletion, in this transaction.
        return jdbc.queryForObject("SELECT id FROM app_user WHERE issuer = ? AND subject = ? FOR UPDATE",
                UUID.class, issuer, subject);
    }

    Map<String, Object> find(Resource resource, UUID owner, UUID id, boolean includeDeleted) {
        var rows = jdbc.queryForList("SELECT * FROM " + resource.table + " WHERE user_id = ? AND id = ?"
                + (includeDeleted ? "" : " AND deleted_at IS NULL"), owner, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<Map<String, Object>> list(Resource resource, UUID owner, UUID mealId, Instant from, Instant to, int limit, int offset) {
        String sql = "SELECT * FROM " + resource.table + " WHERE user_id = ? AND deleted_at IS NULL";
        List<Object> args = new ArrayList<>();
        args.add(owner);
        if (mealId != null) { sql += " AND meal_id = ?"; args.add(mealId); }
        if (from != null) { sql += " AND " + resource.time + " >= ?"; args.add(Timestamp.from(from)); }
        if (to != null) { sql += " AND " + resource.time + " < ?"; args.add(Timestamp.from(to)); }
        sql += " ORDER BY " + resource.time + ", id LIMIT ? OFFSET ?";
        args.add(limit); args.add(offset);
        return jdbc.queryForList(sql, args.toArray());
    }

    void insert(Resource resource, Map<String, Object> fields) {
        String columns = String.join(", ", fields.keySet());
        String placeholders = String.join(", ", Collections.nCopies(fields.size(), "?"));
        jdbc.update("INSERT INTO " + resource.table + " (" + columns + ") VALUES (" + placeholders + ")", fields.values().toArray());
    }

    void update(Resource resource, UUID owner, UUID id, long revision, Map<String, Object> fields) {
        String assignments = String.join(", ", fields.keySet().stream().map(key -> key + " = ?").toList());
        List<Object> args = new ArrayList<>(fields.values());
        args.add(owner); args.add(id); args.add(revision);
        int changed = jdbc.update("UPDATE " + resource.table + " SET " + assignments
                + ", revision = revision + 1 WHERE user_id = ? AND id = ? AND revision = ? AND deleted_at IS NULL", args.toArray());
        if (changed != 1) { throw DomainException.conflict(); }
    }

    boolean hasActiveReferences(UUID owner, UUID meal) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM meal_item WHERE user_id = ? AND meal_id = ? AND deleted_at IS NULL)"
                + " OR EXISTS (SELECT 1 FROM glucose_reading WHERE user_id = ? AND meal_id = ? AND deleted_at IS NULL)",
                Boolean.class, owner, meal, owner, meal));
    }

    static Instant instant(Map<String, Object> row, String field) {
        var value = row.get(field);
        return value == null ? null : ((Timestamp) value).toInstant();
    }
}
