# GSBatch — Documentation

**Generic Spring Batch ingestion framework**
Stack : Java 21 · Spring Boot 3.4.x · Spring Batch 5

> Documentation disponible en : **Français** · [English](README-en.md)

---

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Architecture](#2-architecture)
3. [Référence de configuration YAML](#3-référence-de-configuration-yaml)
4. [Types de sources](#4-types-de-sources)
   - [CSV](#41-csv)
   - [SQL](#42-sql)
   - [REST](#43-rest)
   - [SOAP](#44-soap)
5. [Implémenter une nouvelle source (guide backoffice)](#5-implémenter-une-nouvelle-source-guide-backoffice)
6. [Bonnes pratiques Spring Batch 5](#6-bonnes-pratiques-spring-batch-5)
7. [Tests](#7-tests)
8. [Opérations](#8-opérations)
9. [Diagnostic des erreurs courantes](#9-diagnostic-des-erreurs-courantes)
10. [Feuille de route](#10-feuille-de-route)

---

## 1. Vue d'ensemble

gsbatch est une **bibliothèque** (JAR, non exécutable) qui fournit un framework générique d'ingestion de données piloté par configuration. Les applications consommatrices (ex : backoffice) dépendent de ce JAR et apportent :

- leurs fichiers de configuration YAML (`classpath:ingestion/{sourceName}.yml`)
- leurs beans métier (`{sourceName}Writer`, `{sourceName}Processor`)

### Principe fondamental

> Un seul job générique, paramétré par `sourceName`. Zéro code Java à écrire pour ajouter une nouvelle source.

Le framework fournit :

- Job générique unique avec 3 steps (preprocessing, ingestion, postprocessing)
- Loader YAML avec cache
- Factories : Reader, Processor, Writer
- Builders : CSV, SQL, REST, SOAP

Le framework ne contient pas :

- Logique métier
- Writers ou Processors concrets
- Fichiers YAML spécifiques à une application

---

## 2. Architecture

### Flux d'exécution

```
JobParameters (sourceName, ...)
  → YamlSourceConfigLoader.load(sourceName)
      classpath:ingestion/{sourceName}.yml
  → GenericIngestionJob (3 steps) :
      1. genericPreprocessingStep   @JobScope  → GenericTasklet (SQL ou Java bean)
      2. genericIngestionStep       @JobScope  → Reader → Processor → Writer (@StepScope)
      3. genericPostprocessingStep  @JobScope  → GenericTasklet (SQL ou Java bean)
```

### Composants principaux

#### Job générique

```java
@Bean
public Job genericIngestionJob(JobRepository jobRepository, Step genericIngestionStep) {
    return new JobBuilder("genericIngestionJob", jobRepository)
            .start(genericIngestionStep)
            .build();
}
```

#### Step générique

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

    return new StepBuilder("genericIngestionStep-" + sourceName, jobRepository)
            .<GenericRecord, GenericRecord>chunk(config.getChunkSize(), transactionManager)
            .reader(genericIngestionReader)
            .processor(genericIngestionProcessor)
            .writer(genericIngestionWriter)
            .build();
}
```

#### Beans StepScope

```java
@Bean @StepScope
public ItemReader<GenericRecord> genericIngestionReader(
        @Value("#{jobParameters['sourceName']}") String sourceName) {
    return readerFactory.buildReader(configLoader.load(sourceName));
}

@Bean @StepScope
public ItemProcessor<GenericRecord, GenericRecord> genericIngestionProcessor(
        @Value("#{jobParameters['sourceName']}") String sourceName) {
    return processorFactory.buildProcessor(configLoader.load(sourceName));
}

@Bean @StepScope
public ItemWriter<GenericRecord> genericIngestionWriter(
        @Value("#{jobParameters['sourceName']}") String sourceName) {
    return writerFactory.buildWriter(configLoader.load(sourceName));
}
```

#### YamlSourceConfigLoader

```java
@Component
public class YamlSourceConfigLoader {

    @Cacheable(value = "sourceConfigs", key = "#sourceName")
    public SourceConfig load(String sourceName) {
        // Charge classpath:ingestion/{sourceName}.yml
        // Valide et retourne la config
    }
}
```

Fonctionnalités : cache `@Cacheable`, validation automatique, gestion fine des exceptions, SnakeYAML sécurisé (typé sur `SourceConfig`).

### Modèles de données clés

#### GenericRecord

Conteneur clé-valeur flexible transportant une ligne à travers tous les steps.

```java
public class GenericRecord {
    void    put(String name, Object value)
    Object  get(String name)
    String  getString(String name)
    Integer getInteger(String name)
    Double  getDouble(String name)
    Long    getLong(String name)
    Map<String, Object> getValues()   // unmodifiable
}
```

#### SourceConfig

Racine du fichier YAML. Champs communs à tous les types :

| Champ | Type | Description |
|-------|------|-------------|
| `name` | String | Identifiant de la source (= `sourceName`) |
| `type` | String | `CSV`, `SQL`, `REST`, `SOAP` |
| `chunkSize` | int | Taille des chunks Spring Batch |
| `columns` | List\<ColumnConfig\> | Définition des colonnes |
| `writer` | WriterConfig | Configuration du writer (optionnel) |
| `preprocessing` | TaskletConfig | Pre-processing (optionnel) |
| `postprocessing` | TaskletConfig | Post-processing (optionnel) |

#### ColumnConfig

| Champ | Description |
|-------|-------------|
| `name` | Nom de la colonne |
| `type` | `STRING`, `INTEGER`, `DECIMAL`, `DATE`, `BOOLEAN` |
| `format` | Format pour dates / nombres |
| `required` | Erreur si valeur absente |
| `defaultValue` | Valeur par défaut si absente |
| `jsonPath` | Extraction JsonPath (REST) |
| `xpath` | Extraction XPath (SOAP) |

### Factories

#### GenericItemReaderFactory

| `type` | Builder | Reader retourné |
|--------|---------|-----------------|
| `CSV` | `CsvGenericItemReaderBuilder` | `FlatFileItemReader<GenericRecord>` |
| `SQL` | `SqlGenericItemReaderBuilder` | `JdbcCursorItemReader<GenericRecord>` |
| `REST` | `RestGenericItemReaderBuilder` | `RestGenericItemReader` |
| `SOAP` | `SoapGenericItemReaderBuilder` | `SoapGenericItemReader` |

#### GenericItemProcessorFactory

- Recherche le bean `{sourceName}Processor` → l'utilise s'il existe
- Sinon → pass-through (`item -> item`)

Le processor est toujours **optionnel**.

#### GenericItemWriterFactory

Résolution dans l'ordre :

1. `writer.type=SQL` → `SqlGenericItemWriter` (charge le fichier SQL, lie jobParameters + champs du record)
2. `writer.type=JAVA` → recherche `writer.beanName`
3. Aucune config writer → recherche `{sourceName}Writer` (convention, **obligatoire**)

#### GenericTasklet (pre/post processing)

- `type=SQL` → charge un fichier SQL, lie `jobParameters`, exécute
- `type=JAVA` → recherche `{sourceName}PreprocessingTasklet` / `{sourceName}PostprocessingTasklet`
- `enabled=false` ou absent → no-op

### Résolution des variables

Les deux mécanismes s'appliquent aux URLs, SQL et templates de requête :

- **Bind variables** `:paramName` → résolu depuis `jobParameters`
- **Variables d'environnement** `${VAR_NAME}` → résolu depuis `System.getenv()`

### Chargement des fichiers SQL

`SqlFileLoader` résout dans l'ordre :
1. Chemin absolu
2. Préfixe `classpath:`
3. Chemin relatif au système de fichiers

---

## 3. Référence de configuration YAML

### Structure minimale

```yaml
name: trades
type: CSV
chunkSize: 500

columns:
  - name: trade_id
    type: STRING
    required: true
  - name: amount
    type: DECIMAL
    format: "#0.00"
  - name: trade_date
    type: DATE
    format: "yyyy-MM-dd"
```

### Avec pre/post processing

```yaml
name: trades
type: CSV
chunkSize: 500

preprocessing:
  enabled: true
  type: SQL
  sqlFile: classpath:sql/trades_pre.sql

postprocessing:
  enabled: true
  type: JAVA   # recherche le bean 'tradesPostprocessingTasklet'

columns:
  - name: trade_id
    type: STRING
```

### Avec writer SQL

```yaml
writer:
  type: SQL
  sqlFile: classpath:sql/insert_trades.sql
  onError: SKIP
  skipLimit: 10
```

---

## 4. Types de sources

### 4.1 CSV

```yaml
name: trades
type: CSV
chunkSize: 500
path: "data/trades.csv"
delimiter: ";"
skipHeader: true

columns:
  - name: trade_id
    type: STRING
    required: true
  - name: instrument
    type: STRING
  - name: quantity
    type: INTEGER
  - name: price
    type: DECIMAL
    format: "#0.00"
  - name: trade_date
    type: DATE
    format: "yyyy-MM-dd"
```

**Points d'implémentation :**

- `FlatFileItemReader` implémente `ItemStream` — ne pas appeler `afterPropertiesSet()` manuellement
- Spring Batch gère le lifecycle (`open` / `read` / `update` / `close`)

---

### 4.2 SQL

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

### 4.3 REST

#### Configuration complète

```yaml
name: orders-api
type: REST
chunkSize: 100

rest:
  url: https://api.bank.com/v2/orders
  method: GET

  queryParams:
    status:      :status          # bind variable → jobParameter 'status'
    trade_date:  :process_date    # bind variable → jobParameter 'process_date'
    desk:        :desk

  headers:
    Accept:       application/json
    X-Client-Id:  backoffice-batch

  auth:
    type: API_KEY
    apiKey: ${API_KEY_ORDERS}     # variable d'environnement
    headerName: X-Api-Key

  dataPath: $.data.orders         # JsonPath vers le tableau de résultats

  pagination:
    strategy:  PAGE_SIZE
    pageParam: page
    sizeParam: size
    pageSize:  100
    totalPath: $.meta.total       # optionnel, pour le logging

  retry:
    maxRetries: 3
    retryDelay: 2000              # ms
    retryOnHttpCodes: [429, 503, 504]

columns:
  - name: order_id
    type: STRING
    jsonPath: $.orderId
    required: true
  - name: customer_id
    type: STRING
    jsonPath: $.customer.id       # champ imbriqué
  - name: amount
    type: DECIMAL
    jsonPath: $.totalAmount
  - name: currency
    type: STRING                  # pas de jsonPath → clé JSON = nom de colonne
  - name: order_date
    type: DATE
    format: "yyyy-MM-dd'T'HH:mm:ss'Z'"
    jsonPath: $.createdAt
```

#### Authentification

| Type | Usage | Configuration |
|------|-------|---------------|
| `NONE` | API publique | `auth.type: NONE` |
| `API_KEY` | Clé statique en header | `apiKey: ${VAR}` · `headerName: X-Api-Key` |
| `BEARER` | Token Bearer statique | `bearerToken: ${VAR}` |
| `OAUTH2_CLIENT_CREDENTIALS` | Flow OAuth2 | Non implémenté |

Exemple BEARER :

```yaml
auth:
  type: BEARER
  bearerToken: ${BEARER_TOKEN}
```

Requête HTTP générée :
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### Stratégies de pagination

**NONE** — Requête unique, pas de pagination.

```yaml
pagination:
  strategy: NONE
```

**PAGE_SIZE** — Numéro de page incrémental.

```yaml
pagination:
  strategy: PAGE_SIZE
  pageParam: page
  sizeParam: size
  pageSize:  100
```

Appels générés : `GET /orders?page=0&size=100`, `page=1`, `page=2`...
Arrêt : réponse vide.

**OFFSET_LIMIT** — Offset incrémental.

```yaml
pagination:
  strategy:    OFFSET_LIMIT
  offsetParam: offset
  limitParam:  limit
  pageSize:    100
```

Appels générés : `GET /orders?offset=0&limit=100`, `offset=100`...
Arrêt : réponse vide.

**CURSOR** — Curseur extrait de chaque réponse.

```yaml
pagination:
  strategy:   CURSOR
  cursorParam: cursor
  cursorPath:  $.meta.nextCursor
  pageSize:    100
```

Arrêt : `nextCursor` null ou absent.

**LINK_HEADER** — URL de la page suivante dans le header HTTP `Link`. Non implémenté.

#### Extraction JSON (JsonPath)

`dataPath` localise le tableau de résultats dans la réponse :

| dataPath | Structure JSON attendue |
|----------|------------------------|
| `$.data.orders` | `{"data": {"orders": [...]}}` |
| `$.orders` | `{"orders": [...]}` |
| `$` | `[{...}, {...}]` (tableau racine) |

`jsonPath` sur une colonne mappe une clé JSON vers le nom de colonne :

```yaml
columns:
  - name: order_id
    jsonPath: $.orderId           # JSON key = 'orderId'
  - name: customer_id
    jsonPath: $.customer.id       # extraction imbriquée
  - name: currency
    # pas de jsonPath → JSON key = 'currency'
```

#### Retry

| Code HTTP | Retry ? | Raison |
|-----------|---------|--------|
| 200-299 | Non | Succès |
| 400 | Non | Erreur client |
| 401 | Non | Credentials invalides |
| 403 | Non | Accès refusé |
| 429 | **Oui** | Rate limit |
| 503 | **Oui** | Indisponibilité temporaire |
| 504 | **Oui** | Timeout gateway |

Flux de retry :
```
Tentative 1 : HTTP 429 → attente 2000ms → retry
Tentative 2 : HTTP 429 → attente 2000ms → retry
Tentative 3 : HTTP 429 → attente 2000ms → retry
Tentative 4 : HTTP 429 → ECHEC (maxRetries=3 épuisé)
```

Pour désactiver : `retry.maxRetries: 0`

---

### 4.4 SOAP

```yaml
name: trades-soap
type: SOAP
chunkSize: 200

soap:
  endpoint: https://ws.bank.com/trades
  soapAction: "urn:getTrades"
  soapVersion: "1.1"             # 1.1 ou 1.2

  requestTemplate: |
    <soapenv:Envelope xmlns:soapenv="...">
      <soapenv:Body>
        <getTrades>
          <date>:process_date</date>
        </getTrades>
      </soapenv:Body>
    </soapenv:Envelope>

  auth:
    type: BASIC                  # NONE | BASIC | WS_SECURITY | CUSTOM_HEADER
    username: ${SOAP_USER}
    password: ${SOAP_PASS}

  dataXPath: "//trade"

columns:
  - name: trade_id
    type: STRING
    xpath: "tradeId"
  - name: amount
    type: DECIMAL
    xpath: "amount"
```

---

## 5. Implémenter une nouvelle source (guide backoffice)

Aucune modification dans gsbatch n'est nécessaire pour ajouter une source.

### Checklist

1. Créer `src/main/resources/ingestion/{source}.yml`
2. Créer le fichier de données si applicable (CSV, etc.)
3. Créer le writer `@Component("{source}Writer")` — **obligatoire**
4. Créer le processor `@Component("{source}Processor")` — optionnel
5. Tester avec `sourceName={source}`

### Processor métier (optionnel)

```java
@Component("tradesProcessor")
public class TradesProcessor implements ItemProcessor<GenericRecord, GenericRecord> {

    @Override
    public GenericRecord process(GenericRecord item) throws Exception {
        String tradeId = item.getString("tradeId");
        if (tradeId == null || tradeId.isBlank()) {
            return null;   // null = skip l'enregistrement
        }
        // Enrichissement
        if ("EUR".equals(item.getString("currency"))) {
            item.put("priceUSD", item.getDouble("price") * 1.1);
        }
        return item;
    }
}
```

Convention : `{sourceName}Processor` (ex : `tradesProcessor` pour source `trades`).

### Writer métier (obligatoire)

```java
@Component("tradesWriter")
public class TradesWriter implements ItemWriter<GenericRecord> {

    @Autowired
    private TradeRepository repository;

    @Override
    public void write(Chunk<? extends GenericRecord> chunk) throws Exception {
        List<Trade> trades = chunk.getItems().stream()
            .map(this::toEntity)
            .collect(Collectors.toList());
        repository.saveAll(trades);
    }

    private Trade toEntity(GenericRecord record) {
        Trade trade = new Trade();
        trade.setTradeId(record.getString("tradeId"));
        trade.setQuantity(record.getInteger("quantity"));
        trade.setPrice(record.getDouble("price"));
        return trade;
    }
}
```

Convention : `{sourceName}Writer` (ex : `tradesWriter`).

### Ajouter un type de reader (extension du framework)

Pour ajouter un nouveau type (ex. `JSON`, `XML`) :

1. Créer le modèle en `core/model/` (comme `RestConfig` / `SoapConfig`)
2. L'ajouter dans `SourceConfig`
3. Créer le builder en `core/reader/` implémentant le reader
4. Ajouter un `case` dans `GenericItemReaderFactory`

---

## 6. Bonnes pratiques Spring Batch 5

### API chunk

```java
// Correct (Spring Batch 5)
.chunk(chunkSize, transactionManager)

// Deprecated (Spring Batch 4)
.chunk(chunkSize)
```

### StepScope et proxys CGLIB

Spring crée des proxys CGLIB pour les beans `@StepScope`. Le Step reçoit le proxy, pas l'instance réelle.

```java
// Correct : injecter le proxy, Spring Batch l'utilisera au bon moment
@Bean
public Step step(ItemReader<GenericRecord> reader) {
    return stepBuilder.chunk(100, tm)
        .reader(reader)   // proxy transmis, resolution tardive
        .build();
}

// Incorrect : appeler le bean avec null
ItemReader reader = readerBean(null);   // erreur
```

### Lifecycle ItemStream

Spring Batch appelle automatiquement ces méthodes sur les readers/writers qui implémentent `ItemStream` :

```
open(ExecutionContext)    → initialisation (ouverture fichier, connexion...)
read()                    → lecture répétée
update(ExecutionContext)  → checkpoint après chaque chunk
close()                   → nettoyage
```

Ne jamais appeler `afterPropertiesSet()` ni ces méthodes manuellement.

### @JobScope vs @StepScope

| Scope | Durée de vie | Injection possible |
|-------|-------------|-------------------|
| `@JobScope` | Durée du job | `jobParameters` via `@Value` |
| `@StepScope` | Durée d'un step | `jobParameters` + `stepExecutionContext` |

Les readers, processors et writers doivent être `@StepScope` pour activer la résolution tardive des paramètres.

---

## 7. Tests

Stack : JUnit 5 · Mockito 5 · AssertJ — tests unitaires purs, sans contexte Spring.

```bash
# Lancer les tests
mvn test

# Résultat actuel
Tests run: 168, Failures: 0, Errors: 0, Skipped: 0
```

### Suite de tests

| Classe de test | Tests | Composant couvert |
|----------------|-------|-------------------|
| `GenericRecordTest` | 23 | Conteneur de données, conversions de types |
| `SourceConfigTest` | 26 | Validation YAML (CSV, SQL, REST, SOAP, chunkSize) |
| `WriterConfigTest` | 14 | Validation writer (type, onError, skipLimit) |
| `StepConfigTest` | 11 | Validation pre/post processing |
| `ColumnConfigTest` | 6 | Validation colonne |
| `SqlFileLoaderTest` | 17 | Chargement SQL, bind vars, commentaires, cast PG `::` |
| `YamlSourceConfigLoaderTest` | 8 | Chargement YAML, protection path traversal |
| `GenericItemReaderFactoryTest` | 12 | Dispatch CSV/SQL/REST/SOAP, types inconnus |
| `GenericItemProcessorFactoryTest` | 7 | Pass-through, bean custom, nommage camelCase |
| `GenericItemWriterFactoryTest` | 10 | SQL/JAVA déclaratif, convention bean, camelCase |
| `SqlGenericItemWriterTest` | 4 | Écriture batch, chunk vide |
| `GenericTaskletTest` | 8 | Disabled, SQL, JAVA, type inconnu |

### Couverture de code

Généré avec : `mvn clean verify -P coverage` → `target/site/jacoco/index.html`

| Package | Lignes | Couverture |
|---------|--------|------------|
| `processor` | 35/35 | **100 %** |
| `tasklet` | 62/64 | **97 %** |
| `writer` | 90/94 | **96 %** |
| `config` | 35/52 | 67 % |
| `model` | 199/329 | 60 % |
| `exception` | 2/4 | 50 % |
| `reader` | 120/804 | 15 % |
| `job` | 0/64 | 0 % |
| **Total** | **543/1 446** | **38 %** |

**`reader` (15 %) :** les builders CSV/SQL/REST/SOAP et les readers HTTP nécessiteraient un vrai filesystem, une base de données ou un serveur HTTP mock — tests d'intégration à prévoir.
**`job` (0 %) :** `GenericIngestionJobConfig` requiert un contexte Spring Batch complet (`@SpringBatchTest` + H2) — tests d'intégration à prévoir.

---

## 8. Opérations

### Lancement d'un job

```bash
# Variable d'environnement pour les secrets
export API_KEY_ORDERS=your_api_key_here

# Lancement
java -jar backoffice.jar \
  sourceName=trades \
  process_date=2024-01-15
```

### Commandes Maven

```bash
# Build et installation Maven local
mvn clean install

# Sans tests
mvn clean install -DskipTests

# Build production (vérifie l'absence de dépendances SNAPSHOT)
mvn install -P prod
```

### Profils Maven

| Profil | Rôle |
|--------|------|
| `dev` (défaut) | Développement |
| `prod` | Interdit les dépendances SNAPSHOT |
| `coverage` | Rapport JaCoCo |

### Checklist de déploiement en production

- [ ] Tous les tests passent
- [ ] Cache configuré (Caffeine ou Redis)
- [ ] Niveau de log positionné à INFO
- [ ] Variables d'environnement de secrets définies
- [ ] Fichiers YAML de configuration validés
- [ ] Writers implémentés pour toutes les sources
- [ ] Plan de rollback défini

---

## 9. Diagnostic des erreurs courantes

### `ReaderNotOpenException`

**Cause :** Le reader n'implémente pas `ItemStream` ou `open()` n'a pas été appelé.
**Solution :** Utiliser `FlatFileItemReader` (implémente `ItemStream`).

### `NullPointerException` dans le Step

**Cause :** Bean `@StepScope` appelé avec `null` au lieu d'injecter le proxy.
**Solution :** Injecter le proxy Spring, ne pas l'appeler directement.

### `No writer bean found` / `IllegalStateException: Writer bean required`

**Cause :** Writer métier manquant dans l'application consommatrice.
**Solution :** Créer `@Component("{sourceName}Writer")`.

### `Configuration file not found`

**Cause :** Fichier YAML absent ou mal nommé.
**Solution :** Vérifier `classpath:ingestion/{sourceName}.yml`.

### `Environment variable not found: ${API_KEY_ORDERS}`

**Cause :** Variable d'environnement non définie avant le lancement.
**Solution :** `export API_KEY_ORDERS=your_key_here` avant de démarrer le job.

### `Bind variable not found: ':status'`

**Cause :** Job parameter non fourni en ligne de commande.
**Solution :** Ajouter `status=NEW` aux paramètres du job.

### `JsonPath '$.data.orders' returned null`

**Cause :** Le JsonPath ne correspond pas à la structure JSON réelle.
**Solution :**
1. Activer les logs DEBUG pour afficher la réponse brute
2. Tester le JsonPath sur https://jsonpath.com
3. Ajuster `dataPath` pour correspondre à la structure réelle

### HTTP 401 Unauthorized

**Cause :** Clé API ou token invalide/expiré.
**Solution :** Vérifier la valeur de la variable d'environnement, régénérer si nécessaire.

### HTTP 429 Too Many Requests

**Cause :** Rate limit dépassé.
**Solution :**

```yaml
pagination:
  pageSize: 50         # réduit depuis 100
retry:
  retryDelay: 5000     # augmenté depuis 2000ms
```

### Chunk size ignoré

**Cause :** Valeur codée en dur au lieu d'être lue depuis le YAML.
**Solution :** `config.getChunkSize()` dans le step builder.

---

## 10. Feuille de route

### Court terme

- Skip / Retry policies configurables dans le YAML
- Validation des job parameters au démarrage
- Listeners pour le monitoring (read count, write count, durée)

### Moyen terme

- Métriques Micrometer / dashboards Grafana
- Support lecture JSON et XML natifs
- Partitioning pour la parallélisation
- Support OAuth2 client credentials (REST)
- Support LINK_HEADER pagination (REST)

### Long terme

- Connecteurs cloud : S3, Azure Blob, GCS
- Ingestion incrémentale (CDC)
- Détection automatique de schéma

---

## Licence

Ce projet est distribué sous la licence **Apache License, Version 2.0**.
Voir le fichier [LICENSE](LICENSE) pour le texte complet.

Copyright 2026 Makalisio Contributors

---

*Documentation consolidée — dernière mise à jour : 1er mars 2026*