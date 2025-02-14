// ------------------------------------------------------------------------------
// Copyright (c) 2017 Microsoft Corporation
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sub-license, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
// ------------------------------------------------------------------------------

package com.microsoft.graph.serializer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.microsoft.graph.http.BaseCollectionPage;
import com.microsoft.graph.http.BaseCollectionResponse;
import com.microsoft.graph.logger.ILogger;
import com.microsoft.graph.core.DateOnly;

import com.microsoft.graph.core.TimeOfDay;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.text.ParseException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.Duration;

/**
 * Produce GSON instances that can parse HTTP responses
 */
final class GsonFactory {

	protected static String PARSING_MESSAGE = "Parsing issue on ";

    /**
     * Default constructor
     */
    private GsonFactory() {
    }

    /**
     * Creates an instance of GSON
     *
     * @param logger the logger
     * @return the new instance
     */
    @Nonnull
    public static Gson getGsonInstance(@Nonnull final ILogger logger) {
        return getGsonInstance(logger, false);
    }

    /**
     * Creates an instance of GSON
     *
     * Serializing of null values can have side effects on the service behavior.
     * Sending null values in a PATCH request might reset existing values on the service side.
     * Sending null values in a POST request might prevent the service from assigning default values to the properties.
     * It is not recommended to send null values to the service in general and this setting should only be used when serializing information for a local store.
     *
     * @param logger         the logger
     * @param serializeNulls the setting of whether or not to serialize the null values in the JSON object
     * @return the new instance
     */
    @Nonnull
    public static Gson getGsonInstance(@Nonnull final ILogger logger, final boolean serializeNulls) {
        Objects.requireNonNull(logger, "parameter logger cannot be null");
        final JsonSerializer<OffsetDateTime> calendarJsonSerializer = (src, typeOfSrc, context) -> {
            if (src == null) {
                return null;
            }
            try {
                return new JsonPrimitive(OffsetDateTimeSerializer.serialize(src));
            } catch (final Exception e) {
                logger.logError(PARSING_MESSAGE + src, e);
                return null;
            }
        };

        final JsonDeserializer<OffsetDateTime> calendarJsonDeserializer = (json, typeOfT, context) -> {
            if (json == null) {
                return null;
            }
            try {
                return OffsetDateTimeSerializer.deserialize(json.getAsString());
            } catch (final ParseException e) {
                logger.logError(PARSING_MESSAGE + json.getAsString(), e);
                return null;
            }
        };

        final JsonSerializer<byte[]> byteArrayJsonSerializer = (src, typeOfSrc, context) -> {
            if (src == null) {
                return null;
            }
            try {
                return new JsonPrimitive(ByteArraySerializer.serialize(src));
            } catch (final Exception e) {
                logger.logError(PARSING_MESSAGE + Arrays.toString(src), e);
                return null;
            }
        };

        final JsonDeserializer<byte[]> byteArrayJsonDeserializer = (json, typeOfT, context) -> {
            if (json == null) {
                return null;
            }
            try {
                return ByteArraySerializer.deserialize(json.getAsString());
            } catch (final ParseException e) {
                logger.logError(PARSING_MESSAGE + json.getAsString(), e);
                return null;
            }
        };

        final JsonSerializer<DateOnly> dateJsonSerializer = (src, typeOfSrc, context) -> {
            if (src == null) {
                return null;
            }
            return new JsonPrimitive(src.toString());
        };

        final JsonDeserializer<DateOnly> dateJsonDeserializer = (json, typeOfT, context) -> {
            if (json == null) {
                return null;
            }

            try {
                return DateOnly.parse(json.getAsString());
            } catch (final ParseException e) {
                logger.logError(PARSING_MESSAGE + json.getAsString(), e);
                return null;
            }
        };
        final EnumSetSerializer eSetSerializer = new EnumSetSerializer(logger);

        final JsonSerializer<EnumSet<?>> enumSetJsonSerializer = (src, typeOfSrc, context) -> {
            if (src == null || src.isEmpty()) {
                return null;
            }

            return eSetSerializer.serialize(src);
        };

        final JsonDeserializer<EnumSet<?>> enumSetJsonDeserializer = (json, typeOfT, context) -> {
            if (json == null) {
                return null;
            }

            return eSetSerializer.deserialize(typeOfT, json.getAsString());
        };

        final JsonSerializer<Duration> durationJsonSerializer = (src, typeOfSrc, context) -> new JsonPrimitive(src.toString());

        final JsonDeserializer<Duration> durationJsonDeserializer = (json, typeOfT, context) -> {
            try {
                return DatatypeFactory.newInstance().newDuration(json.getAsString());
            } catch (Exception e) {
                return null;
            }
        };

        final JsonSerializer<BaseCollectionPage<?, ?>> collectionPageSerializer = (src, typeOfSrc, context) -> CollectionPageSerializer.serialize(src, logger);

        final JsonDeserializer<BaseCollectionPage<?, ?>> collectionPageDeserializer = (json, typeOfT, context) -> CollectionPageSerializer.deserialize(json, typeOfT, logger);
        final JsonDeserializer<BaseCollectionResponse<?>> collectionResponseDeserializer = (json, typeOfT, context) -> CollectionResponseDeserializer.deserialize(json, typeOfT, logger);

        final JsonDeserializer<TimeOfDay> timeOfDayJsonDeserializer = (json, typeOfT, context) -> {
            try {
                return TimeOfDay.parse(json.getAsString());
            } catch (Exception e) {
                return null;
            }
        };

        final JsonSerializer<TimeOfDay> timeOfDayJsonSerializer = (src, typeOfSrc, context) -> new JsonPrimitive(src.toString());

        final JsonDeserializer<Boolean> booleanJsonDeserializer = (json, typeOfT, context) -> EdmNativeTypeSerializer.deserialize(json, Boolean.class, logger);

        final JsonDeserializer<String> stringJsonDeserializer = (json, typeOfT, context) -> EdmNativeTypeSerializer.deserialize(json, String.class, logger);

        final JsonDeserializer<BigDecimal> bigDecimalJsonDeserializer = (json, typeOfT, context) -> EdmNativeTypeSerializer.deserialize(json, BigDecimal.class, logger);

        final JsonDeserializer<Integer> integerJsonDeserializer = (json, typeOfT, context) -> EdmNativeTypeSerializer.deserialize(json, Integer.class, logger);

        final JsonDeserializer<Long> longJsonDeserializer = (json, typeOfT, context) -> EdmNativeTypeSerializer.deserialize(json, Long.class, logger);

        final JsonDeserializer<UUID> uuidJsonDeserializer = (json, typeOfT, context) -> EdmNativeTypeSerializer.deserialize(json, UUID.class, logger);

        final JsonDeserializer<Float> floatJsonDeserializer = (json, typeOfT, context) -> EdmNativeTypeSerializer.deserialize(json, Float.class, logger);

        GsonBuilder builder = new GsonBuilder();
        if (serializeNulls) {
            builder.serializeNulls();
        }
        return builder
                .excludeFieldsWithoutExposeAnnotation()
                .registerTypeAdapter(Boolean.class, booleanJsonDeserializer)
                .registerTypeAdapter(String.class, stringJsonDeserializer)
                .registerTypeAdapter(Float.class, floatJsonDeserializer)
                .registerTypeAdapter(Integer.class, integerJsonDeserializer)
                .registerTypeAdapter(BigDecimal.class, bigDecimalJsonDeserializer)
                .registerTypeAdapter(UUID.class, uuidJsonDeserializer)
                .registerTypeAdapter(Long.class, longJsonDeserializer)
                .registerTypeAdapter(OffsetDateTime.class, calendarJsonSerializer)
                .registerTypeAdapter(OffsetDateTime.class, calendarJsonDeserializer)
                .registerTypeAdapter(GregorianCalendar.class, calendarJsonSerializer)
                .registerTypeAdapter(GregorianCalendar.class, calendarJsonDeserializer)
                .registerTypeAdapter(byte[].class, byteArrayJsonDeserializer)
                .registerTypeAdapter(byte[].class, byteArrayJsonSerializer)
                .registerTypeAdapter(DateOnly.class, dateJsonSerializer)
                .registerTypeAdapter(DateOnly.class, dateJsonDeserializer)
                .registerTypeAdapter(EnumSet.class, enumSetJsonSerializer)
                .registerTypeAdapter(EnumSet.class, enumSetJsonDeserializer)
                .registerTypeAdapter(Duration.class, durationJsonSerializer)
                .registerTypeAdapter(Duration.class, durationJsonDeserializer)
                .registerTypeHierarchyAdapter(BaseCollectionPage.class, collectionPageSerializer)
                .registerTypeHierarchyAdapter(BaseCollectionPage.class, collectionPageDeserializer)
                .registerTypeHierarchyAdapter(BaseCollectionResponse.class, collectionResponseDeserializer)
                .registerTypeAdapter(TimeOfDay.class, timeOfDayJsonDeserializer)
                .registerTypeAdapter(TimeOfDay.class, timeOfDayJsonSerializer)
                .registerTypeAdapterFactory(new FallbackTypeAdapterFactory(logger))
                .create();
    }
}
