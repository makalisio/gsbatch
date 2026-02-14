# 🏗️ ARCHITECTURE GSBATCH & BACKOFFICE - RÉFÉRENCE COMPLÈTE

## 📋 VUE D'ENSEMBLE

### Modules du projet
1. **gsbatch** → Framework générique Spring Batch 5 / Spring Boot 3
2. **backoffice** → Application métier utilisant gsbatch

### Principe fondamental
Un framework **générique, extensible, indépendant du métier** permettant de créer des jobs d'ingestion configurables via YAML.

---

## 🎯 ARCHITECTURE GSBATCH (Framework)

### Responsabilités du framework
Le framework fournit **uniquement** les éléments génériques :
- ✅ Job générique unique
- ✅ Step générique paramétrable
- ✅ Loader YAML
- ✅ Modèles de données génériques
- ✅ Factories pour construire les composants
- ✅ Builders (CSV, SQL à venir...)

### Ce que le framework NE FAIT PAS
- ❌ Logique métier
- ❌ Writers concrets
- ❌ Processors métier
- ❌ Fichiers YAML spécifiques

---

## 📦 COMPOSANTS GSBATCH

### 1. Job Générique

```java
@Bean
public Job genericIngestionJob(JobRepository jobRepository,
                               Step genericIngestionStep) {
    return new JobBuilder("genericIngestionJob", jobRepository)
            .start(genericIngestionStep)
            .build();
}
```

**Caractéristiques :**
- Un seul job pour toutes les sources
- Configuration dynamique via `sourceName`
- Pas de logique métier

---

### 2. Step Générique

```java
@Bean
public Step genericIngestionStep(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        ItemReader<GenericRecord> genericIngestionReader,
        ItemProcessor<GenericRecord, GenericRecord> genericIngestionProcessor,
        ItemWriter<GenericRecord> genericIngestionWriter,
        @Value("#{jobParameters['sourceName']}") String sourceName) {
    
    SourceConfig config = configLoader.load(sourceName);
    int chunkSize = config.getChunkSize();
    
    return new StepBuilder("genericIngestionStep-" + sourceName, jobRepository)
            .<GenericRecord, GenericRecord>chunk(chunkSize, transactionManager)
            .reader(genericIngestionReader)
            .processor(genericIngestionProcessor)
            .writer(genericIngestionWriter)
            .build();
}
```

**Points critiques :**
- ✅ Reçoit les **proxys** StepScope (pas les instances)
- ✅ ChunkSize vient du YAML
- ✅ Utilise `chunk(int, TransactionManager)` (API Spring Batch 5)
- ✅ Pas de cast vers `FlatFileItemReader`

---

### 3. Beans StepScope

```java
@Bean
@StepScope
public ItemReader<GenericRecord> genericIngestionReader(
        @Value("#{jobParameters['sourceName']}") String sourceName) {
    SourceConfig config = configLoader.load(sourceName);
    return readerFactory.buildReader(config);
}

@Bean
@StepScope
public ItemProcessor<GenericRecord, GenericRecord> genericIngestionProcessor(
        @Value("#{jobParameters['sourceName']}") String sourceName) {
    SourceConfig config = configLoader.load(sourceName);
    return processorFactory.buildProcessor(config);
}

@Bean
@StepScope
public ItemWriter<GenericRecord> genericIngestionWriter(
        @Value("#{jobParameters['sourceName']}") String sourceName) {
    SourceConfig config = configLoader.load(sourceName);
    return writerFactory.buildWriter(config);
}
```

**Règles impératives :**
- ✅ Toujours `@StepScope`
- ✅ Recevoir `sourceName` via `@Value("#{jobParameters['sourceName']}")`
- ✅ Charger la config via `configLoader.load(sourceName)`
- ✅ Retourner le type interface (`ItemReader`, pas `FlatFileItemReader`)

---

### 4. YamlSourceConfigLoader

```java
@Component
public class YamlSourceConfigLoader {
    
    @Cacheable(value = "sourceConfigs", key = "#sourceName")
    public SourceConfig load(String sourceName) {
        String path = String.format("classpath:ingestion/%s.yml", sourceName);
        Resource resource = resourceLoader.getResource(path);
        
        // Charge et valide le YAML
        SourceConfig config = yaml.loadAs(inputStream, SourceConfig.class);
        config.validate();
        
        return config;
    }
}
```

**Fonctionnalités :**
- ✅ Cache les configs chargées
- ✅ Validation automatique
- ✅ Gestion d'erreurs précise
- ✅ Sécurité SnakeYAML

---

### 5. Modèle de données

#### GenericRecord
```java
public class GenericRecord {
    private final Map<String, Object> values = new HashMap<>();
    
    public void put(String name, Object value)
    public Object get(String name)
    public String getString(String name)
    public Integer getInteger(String name)
    public Double getDouble(String name)
    public Long getLong(String name)
    public Map<String, Object> getValues() // Unmodifiable
}
```

**Utilisation :**
- Structure de données flexible
- Conversions de types incluses
- Encapsulation correcte

#### SourceConfig
```yaml
name: trades
type: CSV
chunkSize: 500
path: "data/trades.csv"
delimiter: ";"
skipHeader: true
columns:
  - name: tradeId
    type: STRING
  - name: quantity
    type: INTEGER
  - name: price
    type: DECIMAL
    format: "#0.00"
```

#### ColumnConfig
```java
public class ColumnConfig {
    private String name;
    private String type;      // STRING, INTEGER, DECIMAL, DATE, BOOLEAN
    private String format;    // Format pour dates/nombres
    private boolean required; // Champ obligatoire
    private String defaultValue;
}
```

---

### 6. Factories

#### GenericItemReaderFactory
```java
@Component
public class GenericItemReaderFactory {
    
    public ItemReader<GenericRecord> buildReader(SourceConfig config) {
        switch (config.getType().toUpperCase()) {
            case "CSV":
                return csvReaderBuilder.build(config);
            case "SQL":
                throw new UnsupportedOperationException("Not yet implemented");
            // Autres types à venir...
        }
    }
}
```

#### GenericItemProcessorFactory
```java
@Component
public class GenericItemProcessorFactory {
    
    public ItemProcessor<GenericRecord, GenericRecord> buildProcessor(SourceConfig config) {
        String beanName = config.getName() + "Processor";
        
        if (!applicationContext.containsBean(beanName)) {
            // Retourne un pass-through processor
            return item -> item;
        }
        
        return (ItemProcessor<GenericRecord, GenericRecord>) 
               applicationContext.getBean(beanName);
    }
}
```

**Comportement :**
- Si processor métier existe → l'utilise
- Sinon → pass-through (item → item)

#### GenericItemWriterFactory
```java
@Component
public class GenericItemWriterFactory {
    
    public ItemWriter<GenericRecord> buildWriter(SourceConfig config) {
        String beanName = config.getName() + "Writer";
        
        if (!applicationContext.containsBean(beanName)) {
            throw new IllegalStateException(
                "Writer bean required: " + beanName
            );
        }
        
        return (ItemWriter<GenericRecord>) 
               applicationContext.getBean(beanName);
    }
}
```

**Comportement :**
- Writer métier OBLIGATOIRE
- Exception si absent

---

### 7. CsvGenericItemReaderBuilder

```java
@Component
public class CsvGenericItemReaderBuilder {
    
    public FlatFileItemReader<GenericRecord> build(SourceConfig config) {
        FlatFileItemReader<GenericRecord> reader = new FlatFileItemReader<>();
        
        reader.setName("csvReader-" + config.getName());
        reader.setResource(new FileSystemResource(config.getPath()));
        reader.setLinesToSkip(config.isSkipHeader() ? 1 : 0);
        
        // Tokenizer
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setDelimiter(config.getDelimiter());
        tokenizer.setNames(config.getColumnNames());
        
        // LineMapper
        DefaultLineMapper<GenericRecord> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSet -> {
            GenericRecord record = new GenericRecord();
            config.getColumns().forEach(col -> {
                String value = fieldSet.readString(col.getName());
                record.put(col.getName(), value.trim());
            });
            return record;
        });
        
        reader.setLineMapper(lineMapper);
        
        // NE PAS appeler afterPropertiesSet() ici
        // Spring Batch le fera via ItemStream
        
        return reader;
    }
}
```

**Points critiques :**
- ✅ Retourne `FlatFileItemReader` (qui implémente `ItemStream`)
- ✅ NE PAS appeler `afterPropertiesSet()`
- ✅ Spring Batch gère le lifecycle via `ItemStream`
- ✅ Validation du fichier CSV

---

## 🏢 ARCHITECTURE BACKOFFICE (Application métier)

### Responsabilités du backoffice
- ✅ Créer les processors métier
- ✅ Créer les writers métier
- ✅ Fournir les fichiers YAML
- ✅ Fournir les données CSV/SQL

### Ce que le backoffice NE FAIT PAS
- ❌ Modifier le framework gsbatch
- ❌ Créer des jobs ou steps
- ❌ Gérer les transactions Spring Batch

---

## 📝 COMPOSANTS BACKOFFICE

### 1. Processor métier

```java
@Component("tradesProcessor")
public class TradesProcessor implements ItemProcessor<GenericRecord, GenericRecord> {
    
    @Override
    public GenericRecord process(GenericRecord item) throws Exception {
        // Logique métier : validation, enrichissement, transformation
        
        String tradeId = item.getString("tradeId");
        if (tradeId == null || tradeId.isBlank()) {
            return null; // Skip invalid items
        }
        
        // Enrichissement
        Double price = item.getDouble("price");
        String currency = item.getString("currency");
        
        if ("EUR".equals(currency)) {
            item.put("priceUSD", price * 1.1); // Conversion fictive
        }
        
        return item;
    }
}
```

**Convention de nommage :**
- Bean name = `{sourceName}Processor`
- Exemple : `tradesProcessor` pour la source `trades`

---

### 2. Writer métier

```java
@Component("tradesWriter")
public class TradesWriter implements ItemWriter<GenericRecord> {
    
    @Autowired
    private TradeRepository repository;
    
    @Override
    public void write(Chunk<? extends GenericRecord> chunk) throws Exception {
        List<Trade> trades = chunk.getItems().stream()
            .map(this::convertToEntity)
            .collect(Collectors.toList());
        
        repository.saveAll(trades);
        
        log.info("Written {} trades to database", trades.size());
    }
    
    private Trade convertToEntity(GenericRecord record) {
        Trade trade = new Trade();
        trade.setTradeId(record.getString("tradeId"));
        trade.setInstrument(record.getString("instrument"));
        trade.setQuantity(record.getInteger("quantity"));
        trade.setPrice(record.getDouble("price"));
        trade.setCurrency(record.getString("currency"));
        // ... mapping complet
        return trade;
    }
}
```

**Convention de nommage :**
- Bean name = `{sourceName}Writer`
- Exemple : `tradesWriter` pour la source `trades`

---

### 3. Fichier YAML

**Emplacement :**
```
backoffice/src/main/resources/ingestion/trades.yml
```

**Contenu :**
```yaml
name: trades
type: CSV
chunkSize: 500
path: "data/trades.csv"
delimiter: ";"
skipHeader: true
columns:
  - name: tradeId
    type: STRING
    required: true
  - name: instrument
    type: STRING
    required: true
  - name: quantity
    type: INTEGER
  - name: price
    type: DECIMAL
    format: "#0.00"
  - name: currency
    type: STRING
  - name: tradeDate
    type: DATE
    format: "yyyy-MM-dd"
  - name: counterparty
    type: STRING
```

---

### 4. Fichier CSV

**Emplacement :**
```
backoffice/data/trades.csv
```

**Contenu :**
```csv
tradeId;instrument;quantity;price;currency;tradeDate;counterparty
T001;AAPL;100;189.50;USD;2024-01-15;GOLDMAN
T002;MSFT;50;320.10;USD;2024-01-16;JPM
T003;TSLA;20;245.00;USD;2024-01-17;BNP
T004;GOOGL;75;140.30;USD;2024-01-18;CITI
T005;AMZN;30;175.20;USD;2024-01-19;BARCLAYS
```

---

## 🚀 EXÉCUTION

### Commande de lancement

```bash
java -jar backoffice.jar \
  --job.name=genericIngestionJob \
  sourceName=trades
```

### Paramètres
- `--job.name` : Nom du job (toujours `genericIngestionJob`)
- `sourceName` : Nom de la source (ex: `trades`, `orders`, `positions`)

### Flux d'exécution

1. Spring Boot démarre le backoffice
2. Spring Batch lit le paramètre `sourceName=trades`
3. Le Step reçoit le paramètre via SpEL
4. Les beans StepScope sont créés avec `sourceName`
5. `YamlSourceConfigLoader.load("trades")` charge `trades.yml`
6. `GenericItemReaderFactory` construit le CSV reader
7. `GenericItemProcessorFactory` trouve `tradesProcessor`
8. `GenericItemWriterFactory` trouve `tradesWriter`
9. Le job s'exécute avec chunk de 500 (config YAML)
10. Les données sont écrites en base

---

## ⚠️ RÈGLES CRITIQUES

### Spring Batch 5

#### ✅ À FAIRE
```java
// Utiliser la nouvelle API chunk
.chunk(chunkSize, transactionManager)

// Les readers doivent être ItemStream
FlatFileItemReader implements ItemStream

// Beans StepScope avec proxys
@Bean
@StepScope
public ItemReader<GenericRecord> reader(
    @Value("#{jobParameters['sourceName']}") String sourceName
) { ... }
```

#### ❌ À ÉVITER
```java
// Ancienne API dépréciée
.chunk(chunkSize)

// Cast vers implementation concrete
FlatFileItemReader reader = (FlatFileItemReader) genericReader;

// Appeler afterPropertiesSet() manuellement
reader.afterPropertiesSet(); // Spring Batch le fait !

// Passer null aux beans StepScope
ItemReader reader = readerBean(null); // ❌ ERREUR
```

---

### StepScope

#### Problème des proxys

Spring crée des **proxys CGLIB** pour les beans `@StepScope`.

**❌ Erreur commune :**
```java
@Bean
public Step step(ItemReader<GenericRecord> reader) {
    // reader est un proxy
    // Appeler reader.read() ici → ERREUR (contexte manquant)
}
```

**✅ Solution :**
```java
@Bean
public Step step(ItemReader<GenericRecord> reader) {
    // Juste injecter le proxy
    // Spring Batch l'utilisera au bon moment
    return stepBuilder.chunk(100, tm)
        .reader(reader)  // OK : proxy transmis
        .build();
}
```

---

### ItemStream Lifecycle

```
1. open(ExecutionContext)    → Initialisation (ex: ouvrir fichier)
2. read()                     → Lecture répétée
3. update(ExecutionContext)   → Checkpoint après chunk
4. close()                    → Nettoyage (fermer fichier)
```

**Le framework NE DOIT PAS** appeler ces méthodes manuellement.  
Spring Batch les appelle automatiquement.

---

## 🔧 EXTENSIONS FUTURES

### Support SQL

```java
@Component
public class SqlGenericItemReaderBuilder {
    
    public JdbcCursorItemReader<GenericRecord> build(SourceConfig config) {
        JdbcCursorItemReader<GenericRecord> reader = new JdbcCursorItemReader<>();
        
        reader.setDataSource(dataSource);
        reader.setSql(config.getQuery());
        reader.setRowMapper((rs, rowNum) -> {
            GenericRecord record = new GenericRecord();
            config.getColumns().forEach(col -> {
                try {
                    Object value = rs.getObject(col.getName());
                    record.put(col.getName(), value);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            return record;
        });
        
        return reader;
    }
}
```

**YAML SQL :**
```yaml
name: orders
type: SQL
chunkSize: 1000
query: "SELECT order_id, customer_id, amount FROM orders WHERE status = 'NEW'"
columns:
  - name: order_id
    type: STRING
  - name: customer_id
    type: STRING
  - name: amount
    type: DECIMAL
```

---

### Support JSON

```java
@Component
public class JsonGenericItemReaderBuilder {
    
    public JsonItemReader<GenericRecord> build(SourceConfig config) {
        JsonItemReader<GenericRecord> reader = new JsonItemReader<>();
        
        reader.setResource(new FileSystemResource(config.getPath()));
        reader.setJsonObjectReader(new JacksonJsonObjectReader<>(GenericRecord.class));
        
        return reader;
    }
}
```

---

### Support XML

```java
@Component
public class XmlGenericItemReaderBuilder {
    
    public StaxEventItemReader<GenericRecord> build(SourceConfig config) {
        StaxEventItemReader<GenericRecord> reader = new StaxEventItemReader<>();
        
        reader.setResource(new FileSystemResource(config.getPath()));
        reader.setFragmentRootElementName(config.getRootElement());
        reader.setUnmarshaller(createUnmarshaller());
        
        return reader;
    }
}
```

---

## 🧪 TESTS

### Test d'un reader

```java
@SpringBatchTest
@SpringBootTest
class CsvGenericItemReaderBuilderTest {
    
    @Autowired
    private CsvGenericItemReaderBuilder builder;
    
    @Test
    void shouldReadCsvFile() throws Exception {
        // Given
        SourceConfig config = createTestConfig();
        
        // When
        FlatFileItemReader<GenericRecord> reader = builder.build(config);
        reader.open(new ExecutionContext());
        
        GenericRecord record = reader.read();
        
        // Then
        assertThat(record).isNotNull();
        assertThat(record.getString("tradeId")).isEqualTo("T001");
        
        reader.close();
    }
}
```

---

### Test d'intégration complet

```java
@SpringBatchTest
@SpringBootTest
class GenericIngestionJobIT {
    
    @Autowired
    private Job genericIngestionJob;
    
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;
    
    @Test
    void shouldIngestTradesSuccessfully() throws Exception {
        // Given
        JobParameters params = new JobParametersBuilder()
            .addString("sourceName", "trades")
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        
        // When
        JobExecution execution = jobLauncherTestUtils.launchJob(params);
        
        // Then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions()).hasSize(1);
        
        StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getReadCount()).isEqualTo(5);
        assertThat(step.getWriteCount()).isEqualTo(5);
    }
}
```

---

## 📚 GLOSSAIRE

| Terme | Définition |
|-------|------------|
| **gsbatch** | Framework générique Spring Batch |
| **backoffice** | Application métier utilisant gsbatch |
| **sourceName** | Paramètre identifiant une source de données |
| **GenericRecord** | Structure de données flexible (Map) |
| **SourceConfig** | Configuration YAML d'une source |
| **StepScope** | Scope Spring permettant l'injection de jobParameters |
| **ItemStream** | Interface pour le lifecycle reader/writer |
| **Chunk** | Groupe de records traités ensemble |
| **Factory** | Pattern créant les composants (reader/processor/writer) |

---

## 🎯 CHECKLIST CRÉATION NOUVELLE SOURCE

### Dans backoffice :

1. [ ] Créer le fichier YAML `resources/ingestion/{source}.yml`
2. [ ] Créer le fichier de données (CSV, JSON, etc.)
3. [ ] Créer le processor `@Component("{source}Processor")`
4. [ ] Créer le writer `@Component("{source}Writer")`
5. [ ] Créer l'entité JPA si nécessaire
6. [ ] Créer le repository si nécessaire
7. [ ] Tester avec `sourceName={source}`

### Aucune modification dans gsbatch nécessaire ! ✅

---

## 🔍 DIAGNOSTIC ERREURS COURANTES

### ReaderNotOpenException
**Cause :** Reader n'implémente pas `ItemStream` ou `open()` pas appelé  
**Solution :** Utiliser `FlatFileItemReader` (implémente `ItemStream`)

### NullPointerException dans Step
**Cause :** Bean StepScope appelé avec `null`  
**Solution :** Injecter le proxy, ne pas l'appeler directement

### "No writer bean found"
**Cause :** Writer métier manquant dans backoffice  
**Solution :** Créer `@Component("{sourceName}Writer")`

### "Configuration file not found"
**Cause :** YAML manquant ou mal nommé  
**Solution :** Vérifier `resources/ingestion/{sourceName}.yml`

### Chunk size ignoré
**Cause :** Hardcodé au lieu de lire le YAML  
**Solution :** `config.getChunkSize()`

---

**Document de référence complet - Version 1.0**
