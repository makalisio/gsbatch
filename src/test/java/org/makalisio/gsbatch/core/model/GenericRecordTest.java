package org.makalisio.gsbatch.core.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class GenericRecordTest {

    // ── Constructors ─────────────────────────────────────────────────────────

    @Test
    void newRecord_isEmpty() {
        GenericRecord record = new GenericRecord();
        assertThat(record.isEmpty()).isTrue();
        assertThat(record.size()).isZero();
    }

    @Test
    void constructor_withMap_copiesValues() {
        GenericRecord record = new GenericRecord(Map.of("a", 1, "b", "hello"));
        assertThat(record.get("a")).isEqualTo(1);
        assertThat(record.get("b")).isEqualTo("hello");
        assertThat(record.size()).isEqualTo(2);
    }

    @Test
    void constructor_withNullMap_createsEmptyRecord() {
        GenericRecord record = new GenericRecord(null);
        assertThat(record.isEmpty()).isTrue();
    }

    // ── put() / get() ────────────────────────────────────────────────────────

    @Test
    void put_addsValue_andGetReturnsIt() {
        GenericRecord record = new GenericRecord();
        record.put("name", "Alice");
        assertThat(record.get("name")).isEqualTo("Alice");
        assertThat(record.size()).isEqualTo(1);
    }

    @Test
    void put_nullName_throwsIllegalArgumentException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GenericRecord().put(null, "value"))
                .withMessageContaining("Field name cannot be null or blank");
    }

    @Test
    void put_blankName_throwsIllegalArgumentException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GenericRecord().put("  ", "value"));
    }

    @Test
    void put_emptyName_throwsIllegalArgumentException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GenericRecord().put("", "value"));
    }

    @Test
    void get_missingKey_returnsNull() {
        assertThat(new GenericRecord().get("missing")).isNull();
    }

    @Test
    void put_nullValue_isStored() {
        GenericRecord record = new GenericRecord();
        record.put("key", null);
        assertThat(record.containsKey("key")).isTrue();
        assertThat(record.get("key")).isNull();
    }

    // ── getString() ──────────────────────────────────────────────────────────

    @Test
    void getString_returnsToStringOfValue() {
        GenericRecord record = new GenericRecord();
        record.put("count", 42);
        assertThat(record.getString("count")).isEqualTo("42");
    }

    @Test
    void getString_stringValue_returnsSameString() {
        GenericRecord record = new GenericRecord();
        record.put("name", "Bob");
        assertThat(record.getString("name")).isEqualTo("Bob");
    }

    @Test
    void getString_missingKey_returnsNull() {
        assertThat(new GenericRecord().getString("missing")).isNull();
    }

    @Test
    void getString_nullValue_returnsNull() {
        GenericRecord record = new GenericRecord();
        record.put("field", null);
        assertThat(record.getString("field")).isNull();
    }

    // ── getInteger() / getInt() ──────────────────────────────────────────────

    @Test
    void getInteger_fromIntegerValue_returnsInteger() {
        GenericRecord record = new GenericRecord();
        record.put("count", 5);
        assertThat(record.getInteger("count")).isEqualTo(5);
    }

    @Test
    void getInteger_fromStringValue_parsesAndReturns() {
        GenericRecord record = new GenericRecord();
        record.put("count", "42");
        assertThat(record.getInteger("count")).isEqualTo(42);
    }

    @Test
    void getInteger_fromInvalidString_returnsNull() {
        GenericRecord record = new GenericRecord();
        record.put("count", "not-a-number");
        assertThat(record.getInteger("count")).isNull();
    }

    @Test
    void getInteger_missingKey_returnsNull() {
        assertThat(new GenericRecord().getInteger("missing")).isNull();
    }

    @Test
    void getInt_aliasForGetInteger() {
        GenericRecord record = new GenericRecord();
        record.put("v", 7);
        assertThat(record.getInt("v")).isEqualTo(record.getInteger("v"));
    }

    // ── getLong() ────────────────────────────────────────────────────────────

    @Test
    void getLong_fromLongValue_returnsLong() {
        GenericRecord record = new GenericRecord();
        record.put("id", 123456789L);
        assertThat(record.getLong("id")).isEqualTo(123456789L);
    }

    @Test
    void getLong_fromStringValue_parsesLong() {
        GenericRecord record = new GenericRecord();
        record.put("id", "9876543210");
        assertThat(record.getLong("id")).isEqualTo(9876543210L);
    }

    @Test
    void getLong_fromInvalidString_returnsNull() {
        GenericRecord record = new GenericRecord();
        record.put("id", "bad-value");
        assertThat(record.getLong("id")).isNull();
    }

    @Test
    void getLong_missingKey_returnsNull() {
        assertThat(new GenericRecord().getLong("missing")).isNull();
    }

    // ── getDouble() ──────────────────────────────────────────────────────────

    @Test
    void getDouble_fromDoubleValue_returnsDouble() {
        GenericRecord record = new GenericRecord();
        record.put("price", 3.14);
        assertThat(record.getDouble("price")).isEqualTo(3.14);
    }

    @Test
    void getDouble_fromStringValue_parsesDouble() {
        GenericRecord record = new GenericRecord();
        record.put("price", "1.5");
        assertThat(record.getDouble("price")).isEqualTo(1.5);
    }

    @Test
    void getDouble_fromInvalidString_returnsNull() {
        GenericRecord record = new GenericRecord();
        record.put("price", "xyz");
        assertThat(record.getDouble("price")).isNull();
    }

    @Test
    void getDouble_missingKey_returnsNull() {
        assertThat(new GenericRecord().getDouble("missing")).isNull();
    }

    // ── containsKey() ────────────────────────────────────────────────────────

    @Test
    void containsKey_presentKey_returnsTrue() {
        GenericRecord record = new GenericRecord();
        record.put("k", "v");
        assertThat(record.containsKey("k")).isTrue();
    }

    @Test
    void containsKey_absentKey_returnsFalse() {
        assertThat(new GenericRecord().containsKey("absent")).isFalse();
    }

    // ── getValues() ──────────────────────────────────────────────────────────

    @Test
    void getValues_returnsAllEntries() {
        GenericRecord record = new GenericRecord();
        record.put("a", 1);
        record.put("b", "two");
        assertThat(record.getValues()).containsEntry("a", 1).containsEntry("b", "two");
    }

    @Test
    void getValues_isUnmodifiable() {
        GenericRecord record = new GenericRecord();
        record.put("a", 1);
        Map<String, Object> values = record.getValues();
        assertThatThrownBy(() -> values.put("b", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── clear() ──────────────────────────────────────────────────────────────

    @Test
    void clear_removesAllEntries() {
        GenericRecord record = new GenericRecord();
        record.put("a", 1);
        record.put("b", 2);
        record.clear();
        assertThat(record.isEmpty()).isTrue();
        assertThat(record.size()).isZero();
    }

    // ── equals() / hashCode() ────────────────────────────────────────────────

    @Test
    void equals_twoRecordsWithSameValues_areEqual() {
        GenericRecord r1 = new GenericRecord(Map.of("a", 1));
        GenericRecord r2 = new GenericRecord(Map.of("a", 1));
        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }

    @Test
    void equals_differentValues_areNotEqual() {
        GenericRecord r1 = new GenericRecord(Map.of("a", 1));
        GenericRecord r2 = new GenericRecord(Map.of("a", 2));
        assertThat(r1).isNotEqualTo(r2);
    }

    @Test
    void equals_sameInstance_isEqual() {
        GenericRecord record = new GenericRecord(Map.of("x", 1));
        assertThat(record).isEqualTo(record);
    }

    @Test
    void equals_null_isFalse() {
        assertThat(new GenericRecord()).isNotEqualTo(null);
    }

    // ── toString() ───────────────────────────────────────────────────────────

    @Test
    void toString_containsFieldInfo() {
        GenericRecord record = new GenericRecord();
        record.put("key", "value");
        assertThat(record.toString()).contains("key").contains("value");
    }
}
