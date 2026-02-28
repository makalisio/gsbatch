package org.makalisio.gsbatch.core.processor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenericItemProcessorFactoryTest {

    @Mock ApplicationContext applicationContext;
    @Mock ItemProcessor<GenericRecord, GenericRecord> customProcessor;

    private GenericItemProcessorFactory factory;

    @BeforeEach
    void setUp() {
        factory = new GenericItemProcessorFactory(applicationContext);
    }

    // ── Pas de bean personnalisé → pass-through ──────────────────────────────

    @Test
    void buildProcessor_noBeanFound_returnsPassThroughProcessor() throws Exception {
        SourceConfig config = sourceConfig("orders");
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(false);

        ItemProcessor<GenericRecord, GenericRecord> processor = factory.buildProcessor(config);

        GenericRecord record = new GenericRecord();
        record.put("id", 1);
        assertThat(processor.process(record)).isSameAs(record);
    }

    @Test
    void buildProcessor_passThrough_returnsItemUnchanged() throws Exception {
        SourceConfig config = sourceConfig("trades");
        when(applicationContext.containsBean("tradesProcessor")).thenReturn(false);

        ItemProcessor<GenericRecord, GenericRecord> processor = factory.buildProcessor(config);

        GenericRecord record = new GenericRecord();
        record.put("amount", 100.0);
        record.put("currency", "EUR");
        assertThat(processor.process(record)).isSameAs(record);
    }

    // ── Bean personnalisé trouvé ──────────────────────────────────────────────

    @Test
    void buildProcessor_customBeanFound_returnsCustomProcessor() {
        SourceConfig config = sourceConfig("orders");
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(true);
        when(applicationContext.getBean("ordersProcessor")).thenReturn(customProcessor);

        ItemProcessor<GenericRecord, GenericRecord> processor = factory.buildProcessor(config);

        assertThat(processor).isSameAs(customProcessor);
    }

    @Test
    void buildProcessor_beanExistsButNotItemProcessor_throwsIllegalState() {
        SourceConfig config = sourceConfig("orders");
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(true);
        when(applicationContext.getBean("ordersProcessor")).thenReturn("not-a-processor");

        assertThatIllegalStateException()
                .isThrownBy(() -> factory.buildProcessor(config))
                .withMessageContaining("is not an ItemProcessor");
    }

    // ── Nommage camelCase pour sources avec tirets ────────────────────────────

    @Test
    void buildProcessor_hyphenatedSourceName_triesCamelCaseBean() {
        SourceConfig config = sourceConfig("calculator-soap");
        when(applicationContext.containsBean("calculator-soapProcessor")).thenReturn(false);
        when(applicationContext.containsBean("calculatorSoapProcessor")).thenReturn(true);
        when(applicationContext.getBean("calculatorSoapProcessor")).thenReturn(customProcessor);

        ItemProcessor<GenericRecord, GenericRecord> processor = factory.buildProcessor(config);

        assertThat(processor).isSameAs(customProcessor);
    }

    @Test
    void buildProcessor_hyphenatedName_neitherBeanFound_returnsPassThrough() throws Exception {
        SourceConfig config = sourceConfig("exchange-rates");
        when(applicationContext.containsBean("exchange-ratesProcessor")).thenReturn(false);
        when(applicationContext.containsBean("exchangeRatesProcessor")).thenReturn(false);

        ItemProcessor<GenericRecord, GenericRecord> processor = factory.buildProcessor(config);

        // Should return pass-through
        GenericRecord record = new GenericRecord();
        assertThat(processor.process(record)).isSameAs(record);
    }

    // ── Cas d'erreur ─────────────────────────────────────────────────────────

    @Test
    void buildProcessor_nullConfig_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> factory.buildProcessor(null));
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private SourceConfig sourceConfig(String name) {
        SourceConfig sc = new SourceConfig();
        sc.setName(name);
        sc.setType("CSV");
        return sc;
    }
}
