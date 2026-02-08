// org.makalisio.gsbatch.core.model.ColumnConfig
package org.makalisio.gsbatch.core.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ColumnConfig {

    private String name;
    private String type;   // STRING, INTEGER, DECIMAL, DATE...
    private String format; // optionnel

}
