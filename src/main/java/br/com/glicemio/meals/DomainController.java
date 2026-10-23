package br.com.glicemio.meals;

import static br.com.glicemio.meals.DomainRepository.Resource.*;
import static br.com.glicemio.meals.DomainService.fields;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import br.com.glicemio.meals.Contracts.*;

@RestController
@RequestMapping("/api/v1")
public class DomainController {
    private final DomainService service;
    public DomainController(DomainService service) { this.service = service; }

    @PostMapping("/foods")
    ResponseEntity<Object> createFood(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FoodCreate body) {
        return created("/api/v1/foods/" + body.id(), service.create(jwt, FOOD, null, body.id(), fields("name", body.name()), body.createdAt(), body.updatedAt()));
    }
    @GetMapping("/foods")
    List<Object> foods(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue="100") int limit, @RequestParam(defaultValue="0") int offset) {
        return service.list(jwt, FOOD, null, null, null, limit, offset);
    }
    @GetMapping("/foods/{id}")
    Object food(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.get(jwt, FOOD, null, id); }
    @PutMapping("/foods/{id}")
    Object updateFood(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody FoodUpdate body) {
        return service.update(jwt, FOOD, null, id, body.revision(), fields("name", body.name()));
    }
    @DeleteMapping("/foods/{id}")
    ResponseEntity<Void> deleteFood(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam @Positive long revision) {
        service.delete(jwt, FOOD, null, id, revision); return ResponseEntity.noContent().build();
    }

    @PostMapping("/meals")
    ResponseEntity<Object> createMeal(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MealCreate body) {
        return created("/api/v1/meals/" + body.id(), service.create(jwt, MEAL, null, body.id(),
                fields("kind", body.kind(), "at", body.at(), "description", body.description(), "time_reference", body.timeReference()), body.createdAt(), body.updatedAt()));
    }
    @GetMapping("/meals")
    List<Object> meals(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to,
            @RequestParam(defaultValue="100") int limit, @RequestParam(defaultValue="0") int offset) {
        return service.list(jwt, MEAL, null, from, to, limit, offset);
    }
    @GetMapping("/meals/{id}")
    Object meal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.get(jwt, MEAL, null, id); }
    @PutMapping("/meals/{id}")
    Object updateMeal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody MealUpdate body) {
        var data = fields("kind", body.kind(), "at", body.at(), "description", body.description());
        if (body.timeReference() != null) { data.put("time_reference", body.timeReference().name()); }
        return service.update(jwt, MEAL, null, id, body.revision(), data);
    }
    @DeleteMapping("/meals/{id}")
    ResponseEntity<Void> deleteMeal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam @Positive long revision) {
        service.delete(jwt, MEAL, null, id, revision); return ResponseEntity.noContent().build();
    }

    @PostMapping("/meals/{mealId}/items")
    ResponseEntity<Object> createItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mealId, @Valid @RequestBody ItemCreate body) {
        return created("/api/v1/meals/" + mealId + "/items/" + body.id(), service.create(jwt, ITEM, mealId, body.id(),
                fields("food_id", body.foodId(), "food_name", body.foodName(), "quantity", body.quantity(), "unit", body.unit()), body.createdAt(), body.updatedAt()));
    }
    @GetMapping("/meals/{mealId}/items")
    List<Object> items(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mealId,
            @RequestParam(defaultValue="100") int limit, @RequestParam(defaultValue="0") int offset) {
        return service.list(jwt, ITEM, mealId, null, null, limit, offset);
    }
    @GetMapping("/meals/{mealId}/items/{id}")
    Object item(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mealId, @PathVariable UUID id) { return service.get(jwt, ITEM, mealId, id); }
    @PutMapping("/meals/{mealId}/items/{id}")
    Object updateItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mealId, @PathVariable UUID id, @Valid @RequestBody ItemUpdate body) {
        return service.update(jwt, ITEM, mealId, id, body.revision(),
                fields("food_id", body.foodId(), "food_name", body.foodName(), "quantity", body.quantity(), "unit", body.unit()));
    }
    @DeleteMapping("/meals/{mealId}/items/{id}")
    ResponseEntity<Void> deleteItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mealId, @PathVariable UUID id, @RequestParam @Positive long revision) {
        service.delete(jwt, ITEM, mealId, id, revision); return ResponseEntity.noContent().build();
    }

    @PostMapping("/glucose-readings")
    ResponseEntity<Object> createGlucose(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GlucoseCreate body) {
        return created("/api/v1/glucose-readings/" + body.id(), service.create(jwt, GLUCOSE, null, body.id(),
                fields("value", body.value(), "at", body.at(), "context", body.context(), "note", body.note(), "meal_id", body.mealId(), "episode_id", body.episodeId()),
                body.createdAt(), body.updatedAt()));
    }
    @GetMapping("/glucose-readings")
    List<Object> glucoseReadings(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to,
            @RequestParam(defaultValue="100") int limit, @RequestParam(defaultValue="0") int offset) {
        return service.list(jwt, GLUCOSE, null, from, to, limit, offset);
    }
    @GetMapping("/glucose-readings/{id}")
    Object glucose(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.get(jwt, GLUCOSE, null, id); }
    @PutMapping("/glucose-readings/{id}")
    Object updateGlucose(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody GlucoseUpdate body) {
        return service.update(jwt, GLUCOSE, null, id, body.revision(), fields("value", body.value(), "at", body.at(), "context", body.context(),
                "note", body.note(), "meal_id", body.mealId(), "episode_id", body.episodeId()));
    }
    @DeleteMapping("/glucose-readings/{id}")
    ResponseEntity<Void> deleteGlucose(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam @Positive long revision) {
        service.delete(jwt, GLUCOSE, null, id, revision); return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Object> created(String location, Created<Object> result) {
        return result.fresh() ? ResponseEntity.created(URI.create(location)).body(result.body()) : ResponseEntity.ok(result.body());
    }
}
