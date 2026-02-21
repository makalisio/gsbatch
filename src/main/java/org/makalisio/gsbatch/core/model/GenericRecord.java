// org.makalisio.gsbatch.core.model.GenericRecord
package org.makalisio.gsbatch.core.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Generic record that can hold dynamic key-value pairs.
 * Used as a flexible data structure for batch processing.
 *
 * @author Makalisio
 * @since 0.0.1
 */
public class GenericRecord {

    private final Map<String, Object> values = new HashMap<>();

    /**
     * Default constructor - creates an empty record.
     */
    public GenericRecord() {
    }

    /**
     * Constructor with initial values.
     *
     * @param initialValues map of field names to values
     */
    public GenericRecord(Map<String, Object> initialValues) {
        if (initialValues != null) {
            this.values.putAll(initialValues);
        }
    }

    /**
     * Puts a value in the record.
     *
     * @param name the field name
     * @param value the field value
     * @throws IllegalArgumentException if name is null or blank
     */
    public void put(String name, Object value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Field name cannot be null or blank");
        }
        values.put(name, value);
    }

    /**
     * Gets a value from the record.
     *
     * @param name the field name
     * @return the field value, or null if not found
     */
    public Object get(String name) {
        return values.get(name);
    }

    /**
     * Gets a value as a String.
     *
     * @param name the field name
     * @return the value as String, or null if not found
     */
    public String getString(String name) {
        Object value = values.get(name);
        return value != null ? value.toString() : null;
    }

    /**
     * Gets a value as an Integer.
     *
     * @param name the field name
     * @return the value as Integer, or null if not found or cannot be converted
     */
    public Integer getInteger(String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Gets a value as an Integer (alias for getInteger).
     *
     * @param name the field name
     * @return the value as Integer, or null if not found or cannot be converted
     */
    public Integer getInt(String name) {
        return getInteger(name);
    }

    /**
     * Gets a value as a Long.
     *
     * @param name the field name
     * @return the value as Long, or null if not found or cannot be converted
     */
    public Long getLong(String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Gets a value as a Double.
     *
     * @param name the field name
     * @return the value as Double, or null if not found or cannot be converted
     */
    public Double getDouble(String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Double) {
            return (Double) value;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Checks if the record contains a field.
     *
     * @param name the field name
     * @return true if the field exists
     */
    public boolean containsKey(String name) {
        return values.containsKey(name);
    }

    /**
     * Returns an unmodifiable view of the values map.
     *
     * @return unmodifiable map of field values
     */
    public Map<String, Object> getValues() {
        return Collections.unmodifiableMap(values);
    }

    /**
     * Returns the number of fields in the record.
     *
     * @return the number of fields
     */
    public int size() {
        return values.size();
    }

    /**
     * Checks if the record is empty.
     *
     * @return true if the record has no fields
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Clears all fields from the record.
     */
    public void clear() {
        values.clear();
    }

    @Override
    public String toString() {
        return "GenericRecord" + values;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GenericRecord that = (GenericRecord) o;
        return Objects.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }
}