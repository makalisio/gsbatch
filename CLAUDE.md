# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build and install to local Maven repo
mvn clean install

# Skip tests
mvn clean install -DskipTests

# Run tests
mvn test

# Run with coverage report (output: target/site/jacoco/index.html)
mvn clean verify -P coverage

# Production build (enforces no SNAPSHOT dependencies)
mvn install -P prod
```

The test suite lives in `src/test/` — 168 tests, 0 failures. Stack: JUnit 5 + Mockito 5 + AssertJ (pure unit tests, no Spring context). `@SpringBatchTest` + H2 is reserved for future integration tests covering `GenericIngestionJobConfig`.

## Architecture Overview

**gsbatch** is a Spring Batch 5 framework library (JAR, not executable) for configuration-driven data ingestion. Consumers depend on it and provide their own Spring Boot app and YAML source configs.

### Execution Flow

```
JobParameters (sourceName, jobParameters)
  → YamlSourceConfigLoader.load(sourceName)   // loads classpath:ingestion/{sourceName}.yml
  → GenericIngestionJob (3 steps):
      1. genericPreprocessingStep  (@JobScope) → GenericTasklet (SQL or Java bean)
      2. genericIngestionStep      (@JobScope) → Reader → Processor → Writer (all @StepScope)
      3. genericPostprocessingStep (@JobScope) → GenericTasklet (SQL or Java bean)
```

### Reader Factory

`GenericItemReaderFactory` dispatches on `SourceConfig.type`:

| Type | Builder | Returns |
|------|---------|---------|
| `CSV` | `CsvGenericItemReaderBuilder` | `FlatFileItemReader<GenericRecord>` |
| `SQL` | `SqlGenericItemReaderBuilder` | `JdbcCursorItemReader<GenericRecord>` |
| `REST` | `RestGenericItemReaderBuilder` | `RestGenericItemReader` |
| `SOAP` | `SoapGenericItemReaderBuilder` | `SoapGenericItemReader` |

### Writer Resolution (GenericItemWriterFactory)

1. `writer.type=SQL` in YAML → `SqlGenericItemWriter` (loads SQL file, binds from jobParameters + GenericRecord fields)
2. `writer.type=JAVA` in YAML → looks up `writer.beanName` Spring bean
3. No writer config → looks up `{sourceName}Writer` bean (convention, **required**)

### Processor Resolution (GenericItemProcessorFactory)

- Looks for `{sourceName}Processor` bean → uses it if found
- Otherwise → identity/pass-through (processor is always optional)

### Pre/Post Processing (GenericTasklet)

- `type=SQL` → loads SQL from file, binds `jobParameters`, executes
- `type=JAVA` → looks up `{sourceName}PreprocessingTasklet` / `{sourceName}PostprocessingTasklet` bean
- `enabled=false` or absent → no-op

### Key Models

- **`GenericRecord`** — flexible key-value container carrying a single row across all steps. Type-safe accessors: `getString()`, `getInteger()`, `getDouble()`, `getLong()`.
- **`SourceConfig`** — YAML root model; loaded and cached via `@Cacheable("sourceConfigs")`.
- **`ColumnConfig`** — per-column metadata: `type`, `format`, `jsonPath` (REST), `xpath` (SOAP), `required`, `defaultValue`.
- **`RestConfig`** — REST-specific: URL, method, auth (NONE/API_KEY/BEARER/OAUTH2), pagination strategy (PAGE_SIZE/OFFSET_LIMIT/CURSOR/LINK_HEADER), JsonPath data extraction, retry.
- **`SoapConfig`** — SOAP-specific: endpoint, SOAPAction, SOAP version (1.1/1.2), inline XML request template, auth (NONE/BASIC/WS_SECURITY/CUSTOM_HEADER), XPath data extraction.

### @JobScope vs @StepScope

- `@JobScope` — bean lives for the duration of the job; can inject `jobParameters` via `@Value("#{jobParameters['key']}")`.
- `@StepScope` — bean lives for one step; can additionally inject `stepExecutionContext`. Required for readers/processors/writers to enable late binding.

### Variable Resolution Pattern

Both REST and SOAP readers support two substitution mechanisms in URLs, SQL, and request templates:
- **Bind variables** `:paramName` — resolved from `jobParameters`
- **Environment variables** `${VAR_NAME}` — resolved from system environment

### SQL File Loading

`SqlFileLoader` resolves paths in order:
1. Absolute path
2. `classpath:` prefix
3. File system relative path

## Maven Profiles

| Profile | Purpose |
|---------|---------|
| `dev` (default) | Development |
| `prod` | Enforces no SNAPSHOT dependencies |
| `coverage` | JaCoCo code coverage |

## License

Apache License, Version 2.0. See `LICENSE` and `NOTICE` at the repository root.
Every Java source file carries the standard Apache 2.0 header comment.

## Documentation

- `README-fr.md` — documentation complète en français
- `README-en.md` — full documentation in English

## Adding a New Source Type

To add a new reader type (e.g., `JSON`, `XML`):
1. Add a new model class in `core/model/` (like `RestConfig`/`SoapConfig`)
2. Add it to `SourceConfig`
3. Create a builder in `core/reader/` implementing the reader
4. Add a case in `GenericItemReaderFactory`