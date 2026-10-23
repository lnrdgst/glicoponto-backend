package br.com.glicemio.meals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.*;

/** REST contracts retain the Dexie property names. Ownership is never accepted as input. */
public final class Contracts {
    private Contracts() {}

    public enum Unit { g, kg, mg, mL, L, unit, portion, tsp, tbsp, cup }

    public record FoodCreate(@NotNull UUID id, @NotBlank @Size(max=200) String name,
            Instant createdAt, Instant updatedAt) {}
    public record FoodUpdate(@NotBlank @Size(max=200) String name, @NotNull @Positive Long revision) {}
    public record Food(UUID id, String name, Instant createdAt, Instant updatedAt, Instant deletedAt, long revision) {}

    public record MealCreate(@NotNull UUID id, @NotBlank @Size(max=80) String kind, @NotNull Instant at,
            @Size(max=10000) String description, Instant createdAt, Instant updatedAt, TimeReference timeReference) {
        public MealCreate {
            if (timeReference == null) { timeReference = TimeReference.unknown; }
        }
    }
    public record MealUpdate(@NotBlank @Size(max=80) String kind, @NotNull Instant at,
            @Size(max=10000) String description, @NotNull @Positive Long revision, TimeReference timeReference) {}
    public record Meal(UUID id, String kind, Instant at, String description,
            Instant createdAt, Instant updatedAt, Instant deletedAt, long revision, TimeReference timeReference) {}

    public record ItemCreate(@NotNull UUID id, UUID foodId, @NotBlank @Size(max=200) String foodName,
            @NotNull @Positive @Digits(integer=12, fraction=6) BigDecimal quantity, @NotNull Unit unit,
            Instant createdAt, Instant updatedAt) {}
    public record ItemUpdate(UUID foodId, @NotBlank @Size(max=200) String foodName,
            @NotNull @Positive @Digits(integer=12, fraction=6) BigDecimal quantity, @NotNull Unit unit,
            @NotNull @Positive Long revision) {}
    public record Item(UUID id, UUID mealId, UUID foodId, String foodName, BigDecimal quantity, Unit unit,
            Instant createdAt, Instant updatedAt, Instant deletedAt, long revision) {}

    public record GlucoseCreate(@NotNull UUID id,
            @NotNull @Positive @Digits(integer=12, fraction=6) BigDecimal value, @NotNull Instant at,
            @Size(max=200) String context, @Size(max=10000) String note, UUID mealId, UUID episodeId,
            Instant createdAt, Instant updatedAt) {}
    public record GlucoseUpdate(@NotNull @Positive @Digits(integer=12, fraction=6) BigDecimal value,
            @NotNull Instant at, @Size(max=200) String context, @Size(max=10000) String note,
            UUID mealId, UUID episodeId, @NotNull @Positive Long revision) {}
    public record Glucose(UUID id, BigDecimal value, Instant at, String context, String note, UUID mealId,
            UUID episodeId, Instant createdAt, Instant updatedAt, Instant deletedAt, long revision) {}

    public record Created<T>(T body, boolean fresh) {}
}
