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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.microsoft.graph.logger.ILogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The default serializer implementation for the SDK
 */
public class DefaultSerializer implements ISerializer {
	private static final String graphResponseHeadersKey = "graphResponseHeaders";

	/**
	 * The instance of the internal serializer
	 */
	private final Gson gson;

	/**
	 * The logger
	 */
	private final ILogger logger;

	/**
	 * Creates a DefaultSerializer
	 *
	 * @param logger the logger
	 */
	public DefaultSerializer(@Nonnull final ILogger logger) {
		this.logger = Objects.requireNonNull(logger, "parameter logger cannot be null");
		this.gson = GsonFactory.getGsonInstance(logger);
	}

	@Override
	@Nullable
	public <T> T deserializeObject(@Nonnull final String inputString, @Nonnull final Class<T> clazz, @Nullable final Map<String, List<String>> responseHeaders) {
		Objects.requireNonNull(inputString, "parameter inputString cannot be null");
        final JsonElement rawElement = gson.fromJson(inputString, JsonElement.class);
		return deserializeObject(rawElement, clazz, responseHeaders);
	}

	@Override
	@Nullable
	public <T> T deserializeObject(@Nonnull final InputStream inputStream, @Nonnull final Class<T> clazz, @Nullable final Map<String, List<String>> responseHeaders) {
		Objects.requireNonNull(inputStream, "parameter inputStream cannot be null");
        T result = null;
        try (final InputStreamReader streamReader =  new InputStreamReader(inputStream, "UTF-8")) {
			final JsonElement rawElement = gson.fromJson(streamReader, JsonElement.class);
			result = deserializeObject(rawElement, clazz, responseHeaders);
        } catch (IOException ex) {
            //noop we couldn't close the stream reader we just opened and it's ok
        }
        return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	@Nullable
	public <T> T deserializeObject(@Nonnull final JsonElement rawElement, @Nonnull final Class<T> clazz, @Nullable final Map<String, List<String>> responseHeaders) {
		Objects.requireNonNull(rawElement, "parameter rawElement cannot be null");
		Objects.requireNonNull(clazz, "parameter clazz cannot be null");
		final T jsonObject = gson.fromJson(rawElement, clazz);

		// Populate the JSON-backed fields for any annotations that are not in the object model
		if (jsonObject instanceof IJsonBackedObject) {
			logger.logDebug("Deserializing type " + clazz.getSimpleName());
			final JsonObject rawObject = rawElement.isJsonObject() ? rawElement.getAsJsonObject() : null;

			// If there is a derived class, try to get it and deserialize to it
			T jo = jsonObject;
			if (rawElement.isJsonObject()) {
				final Class<?> derivedClass = this.getDerivedClass(rawObject, clazz);
				if (derivedClass != null)
					jo = (T) gson.fromJson(rawElement, derivedClass);
			}

			final IJsonBackedObject jsonBackedObject = (IJsonBackedObject) jo;

			if(rawElement.isJsonObject()) {
				jsonBackedObject.setRawObject(this, rawObject);
				jsonBackedObject.additionalDataManager().setAdditionalData(rawObject);
				setChildAdditionalData(jsonBackedObject,rawObject);
			}

			if (responseHeaders != null) {
				JsonElement convertedHeaders = gson.toJsonTree(responseHeaders);
				jsonBackedObject.additionalDataManager().put(graphResponseHeadersKey, convertedHeaders);
			}
			return jo;
		} else {
			logger.logDebug("Deserializing a non-IJsonBackedObject type " + clazz.getSimpleName());
			return jsonObject;
		}
	}

	/**
	 * Recursively sets additional data for each child object
	 *
	 * @param serializedObject   the parent object whose children will be iterated to set additional data
	 * @param rawJson			the raw json
	 */
	private void setChildAdditionalData(final IJsonBackedObject serializedObject, final JsonObject rawJson) {
		// Use reflection to iterate through fields for eligible Graph children
		if(rawJson != null) {
			for (java.lang.reflect.Field field : serializedObject.getClass().getFields()) {
				try {
					if(field != null) {
						final Object fieldObject = field.get(serializedObject);
						if (fieldObject instanceof HashMap) {
							// If the object is a HashMap, iterate through its children
							@SuppressWarnings("unchecked")
							final HashMap<String, Object> serializableChildren = (HashMap<String, Object>) fieldObject;
							final Iterator<Entry<String, Object>> it = serializableChildren.entrySet().iterator();

							while (it.hasNext()) {
								final Map.Entry<String, Object> pair = (Map.Entry<String, Object>)it.next();
								final Object child = pair.getValue();

								// If the item is a valid Graph object, set its additional data
								if (child instanceof IJsonBackedObject) {
									final AdditionalDataManager childAdditionalDataManager = ((IJsonBackedObject) child).additionalDataManager();
									final JsonElement fieldElement = rawJson.get(field.getName());
									if(fieldElement != null && fieldElement.isJsonObject()
											&& fieldElement.getAsJsonObject().get(pair.getKey()) != null
											&& fieldElement.getAsJsonObject().get(pair.getKey()).isJsonObject()) {
										childAdditionalDataManager.setAdditionalData(fieldElement.getAsJsonObject().get(pair.getKey()).getAsJsonObject());
										setChildAdditionalData((IJsonBackedObject) child,fieldElement.getAsJsonObject().get(pair.getKey()).getAsJsonObject());
									}
								}
							}
						}
						// If the object is a list of Graph objects, iterate through elements
						else if (fieldObject instanceof List) {
							final JsonElement collectionJson = rawJson.get(field.getName());
							final List<?> fieldObjectList = (List<?>) fieldObject;
							if (collectionJson != null && collectionJson.isJsonArray()) {
								final JsonArray rawJsonArray = (JsonArray) collectionJson;
								final int fieldObjectListSize = fieldObjectList.size();
								final int rawJsonArraySize = rawJsonArray.size();
								for (int i = 0; i < fieldObjectListSize && i < rawJsonArraySize; i++) {
									final Object element = fieldObjectList.get(i);
									if (element instanceof IJsonBackedObject) {
										final JsonElement elementRawJson = rawJsonArray.get(i);
										if(elementRawJson != null) {
											setChildAdditionalData((IJsonBackedObject) element, elementRawJson.getAsJsonObject());
										}
									}
								}
								if (rawJsonArraySize != fieldObjectListSize)
									logger.logDebug("rawJsonArray has a size of " + rawJsonArraySize + " and fieldObjectList of " + fieldObjectListSize);
							}
						}
						// If the object is a valid Graph object, set its additional data
						else if (fieldObject instanceof IJsonBackedObject) {
							final IJsonBackedObject serializedChild = (IJsonBackedObject) fieldObject;
							final AdditionalDataManager childAdditionalDataManager = serializedChild.additionalDataManager();
							final JsonElement fieldElement = rawJson.get(field.getName());
							if(fieldElement != null && fieldElement.isJsonObject()) {
								childAdditionalDataManager.setAdditionalData(fieldElement.getAsJsonObject());
								setChildAdditionalData((IJsonBackedObject) fieldObject,fieldElement.getAsJsonObject());
							}
						}
					}
				} catch (IllegalArgumentException | IllegalAccessException e) {
					//Not throwing the IllegalArgumentException as the Serialized Object would still be usable even if the additional data is not set.
					logger.logError("Unable to set child fields of " + serializedObject.getClass().getSimpleName(), e);
					logger.logDebug(rawJson.getAsString());
				}
			}
		}
	}

	/**
	 * Serializes an object into a string
	 *
	 * @param serializableObject the object to convert into a string
	 * @param <T>				the type of the item to be serialized
	 * @return 					 the string representation of that item
	 */
	@Override
	@Nullable
	public <T> String serializeObject(@Nonnull final T serializableObject) {
        Objects.requireNonNull(serializableObject, "parameter serializableObject cannot be null");
        logger.logDebug("Serializing type " + serializableObject.getClass().getSimpleName());
		final JsonElement outJsonTree = gson.toJsonTree(serializableObject);
		if(outJsonTree != null) {
            getChildAdditionalData(serializableObject, outJsonTree);
			return outJsonTree.toString();
		}
        return "";
	}
	/**
	 * Recursively populates additional data for each child object
	 *
	 * @param serializableObject the child to get additional data for
	 * @param outJson			the serialized output JSON to add to
	 */
	@SuppressWarnings("unchecked")
    private void getChildAdditionalData(final Object serializableObject, final JsonElement outJson) {
        if(outJson == null || serializableObject == null || !outJson.isJsonObject())
            return;
        final JsonObject outJsonObject = outJson.getAsJsonObject();
        // Use reflection to iterate through fields for eligible Graph children
        for (java.lang.reflect.Field field : serializableObject.getClass().getFields()) {
            try {
                final Object fieldObject = field.get(serializableObject);
                final JsonElement fieldJsonElement = outJsonObject.get(field.getName());
                if(fieldObject == null || fieldJsonElement == null)
                    continue;

                // If the object is a HashMap, iterate through its children
                if (fieldObject instanceof Map && fieldJsonElement.isJsonObject()) {
                    final Map<String, Object> serializableChildren = (Map<String, Object>) fieldObject;
                    final Iterator<Entry<String, Object>> it = serializableChildren.entrySet().iterator();
                    final JsonObject fieldJsonObject = fieldJsonElement.getAsJsonObject();

                    while (it.hasNext()) {
                        final Map.Entry<String, Object> pair = (Map.Entry<String, Object>)it.next();
                        final Object child = pair.getValue();
                        final JsonElement childJsonElement = fieldJsonObject.get(pair.getKey().toString());
                        // If the item is a valid Graph object, add its additional data
                        getChildAdditionalData(child, childJsonElement);
                    }
                }
                // If the object is a list of Graph objects, iterate through elements
                else if (fieldObject instanceof List && fieldJsonElement.isJsonArray()) {
                    final JsonArray fieldArrayValue = fieldJsonElement.getAsJsonArray();
                    final List<?> fieldObjectList = (List<?>) fieldObject;
                    for (int index = 0; index < fieldObjectList.size(); index++) {
                        final Object item = fieldObjectList.get(index);
                        final JsonElement itemJsonElement = fieldArrayValue.get(index);
                        getChildAdditionalData(item, itemJsonElement);
                    }
                } else if(fieldJsonElement.isJsonObject()) {
                    // If the object is a valid Graph object, add its additional data
                    final JsonObject fieldJsonObject = fieldJsonElement.getAsJsonObject();
                    addAdditionalDataFromJsonObjectToJson(fieldObject, fieldJsonObject);
                }
            } catch (IllegalArgumentException | IllegalAccessException e) {
                logger.logError("Unable to access child fields of " + serializableObject.getClass().getSimpleName(), e);
            }
        }
    }

	/**
	 * Add each non-transient additional data property to the given JSON node
	 *
	 * @param item the object containing additional data
	 * @param itemJsonObject	   the JSON node to add the additional data properties to
	 */
	private void addAdditionalDataFromJsonObjectToJson (final Object item, final JsonObject itemJsonObject) {
		if (item instanceof IJsonBackedObject && itemJsonObject != null) {
			final IJsonBackedObject serializableItem = (IJsonBackedObject) item;
			final AdditionalDataManager itemAdditionalData = serializableItem.additionalDataManager();
			addAdditionalDataFromManagerToJson(itemAdditionalData, itemJsonObject);
			getChildAdditionalData(serializableItem, itemJsonObject);
		}
	}

	/**
	 * Add each non-transient additional data property to the given JSON node
	 *
	 * @param additionalDataManager the additional data bag to iterate through
	 * @param jsonNode			  the JSON node to add the additional data properties to
	 */
	private void addAdditionalDataFromManagerToJson(AdditionalDataManager additionalDataManager, JsonObject jsonNode) {
		for (Map.Entry<String, JsonElement> entry : additionalDataManager.entrySet()) {
			if(!entry.getKey().equals(graphResponseHeadersKey)) {
				jsonNode.add(entry.getKey(), entry.getValue());
			}
		}
	}

	private final static String ODATA_TYPE_KEY = "@odata.type";
	/**
	 * Get the derived class for the given JSON object
	 * This covers scenarios in which the service may return one of several derived types
	 * of a base object, which it defines using the odata.type parameter
	 *
	 * @param jsonObject  the raw JSON object of the response
	 * @param parentClass the parent class the derived class should inherit from
	 * @return			the derived class if found, or null if not applicable
	 */
	@Nullable
	public Class<?> getDerivedClass(@Nonnull final JsonObject jsonObject, @Nullable final Class<?> parentClass) {
        Objects.requireNonNull(jsonObject, "parameter jsonObject cannot be null");
		//Identify the odata.type information if provided
		if (jsonObject.get(ODATA_TYPE_KEY) != null) {
			/** #microsoft.graph.user or #microsoft.graph.callrecords.callrecord */
			final String odataType = jsonObject.get(ODATA_TYPE_KEY).getAsString();
			final int lastDotIndex = odataType.lastIndexOf(".");
			final String derivedType = (odataType.substring(0, lastDotIndex) +
											".models." +
											CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL,
																		odataType.substring(lastDotIndex + 1)))
										.replace("#", "com.");
			try {
				Class<?> derivedClass = Class.forName(derivedType);
				//Check that the derived class inherits from the given parent class
				if (parentClass == null || parentClass.isAssignableFrom(derivedClass)) {
					return derivedClass;
				}
				return null;
			} catch (ClassNotFoundException e) {
				logger.logDebug("Unable to find a corresponding class for derived type " + derivedType + ". Falling back to parent class.");
				//If we cannot determine the derived type to cast to, return null
				//This may happen if the API and the SDK are out of sync
				return null;
			}
		}
		//If there is no defined OData type, return null
		return null;
	}

	/**
	 * Gets the logger in use
	 *
	 * @return a logger
	 */
	@Nullable
	public ILogger getLogger() {
		return logger;
	}
}
