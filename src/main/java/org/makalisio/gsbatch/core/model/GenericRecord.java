// org.makalisio.gsbatch.core.model.GenericRecord
package org.makalisio.gsbatch.core.model;

import java.util.HashMap;
import java.util.Map;


public class GenericRecord {

    private final Map<String, Object> values = new HashMap<>();

    public void put(String name, Object value) {
        values.put(name, value);
    }

    public Object get(String name) {
        return values.get(name);
    }

    public Map<String, Object> values() {
        return values;
    }

    @Override
    public String toString() {
        return "GenericRecord" + values;
    }
}
