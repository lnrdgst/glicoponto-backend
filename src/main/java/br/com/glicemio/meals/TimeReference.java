package br.com.glicemio.meals;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Meaning of meal.at; never implies a second timestamp or an inferred duration. */
@JsonDeserialize(using = TimeReference.Deserializer.class)
public enum TimeReference {
    meal_start, meal_end, unknown;

    public static final class Deserializer extends ValueDeserializer<TimeReference> {
        @Override
        public TimeReference deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                try {
                    return TimeReference.valueOf(parser.getString());
                }
                catch (IllegalArgumentException ignored) {
                    // Do not include the supplied value in the validation error.
                }
            }
            return context.reportInputMismatch(TimeReference.class, "Invalid time reference.");
        }

        @Override
        public TimeReference getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(TimeReference.class, "Time reference must not be null.");
        }

        @Override
        public TimeReference getAbsentValue(DeserializationContext context) {
            return null; // DTO/service distinguish omission from an explicit JSON null.
        }
    }
}
