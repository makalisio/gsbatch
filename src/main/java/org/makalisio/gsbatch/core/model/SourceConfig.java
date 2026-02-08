// org.makalisio.gsbatch.core.model.SourceConfig
package org.makalisio.gsbatch.core.model;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter

public class SourceConfig {

    private String name;          // ex: trades
    private String type;          // CSV, SQL...
    private Integer chunkSize;    // optionnel, défaut 1000

    // CSV
    private String path;
    private String delimiter = ";";
    private boolean skipHeader = true;
    private List<ColumnConfig> columns;



    public Integer getChunkSize() {
        return chunkSize != null ? chunkSize : 1000;
    }

    public String[] getColumnNames() {
        return columns.stream()
                .map(ColumnConfig::getName)
                .toArray(String[]::new);
    }
}
