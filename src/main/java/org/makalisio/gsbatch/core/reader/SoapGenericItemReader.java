/*
 * Copyright 2026 Makalisio Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.makalisio.gsbatch.core.reader;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.ColumnConfig;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SoapConfig;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.util.VariableResolver;
import org.springframework.batch.item.ItemStreamReader;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;

/**
 * SOAP WebService ItemReader with XPath extraction.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>SOAP 1.1/1.2 support</li>
 *   <li>Bind variable resolution (:paramName from jobParameters)</li>
 *   <li>Environment variable resolution (${VAR})</li>
 *   <li>XPath extraction from SOAP response</li>
 *   <li>WS-Security UsernameToken authentication (PasswordText/PasswordDigest)</li>
 *   <li>Single call (no pagination - Q3: NONE)</li>
 *   <li>SOAP Fault causes immediate failure (Q4: B)</li>
 * </ul>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
public class SoapGenericItemReader implements ItemStreamReader<GenericRecord> {

    private final SourceConfig sourceConfig;
    private final SoapConfig soapConfig;
    private final Map<String, Object> jobParameters;
    private final SoapClient soapClient;

    // Buffer for items from SOAP response
    private Queue<GenericRecord> buffer = new LinkedList<>();
    private int itemsRead = 0;
    private boolean responseFetched = false;

    // Cache of compiled DateTimeFormatters, keyed by column format pattern
    private final Map<String, DateTimeFormatter> dateFormatters = new HashMap<>();

    /**
     * @param sourceConfig  source configuration from YAML
     * @param soapConfig    SOAP-specific configuration
     * @param jobParameters job parameters for bind variable resolution
     * @param soapClient    configured SOAP client with auth
     */
    public SoapGenericItemReader(SourceConfig sourceConfig,
                                  SoapConfig soapConfig,
                                  Map<String, Object> jobParameters,
                                  SoapClient soapClient) {
        this.sourceConfig = sourceConfig;
        this.soapConfig = soapConfig;
        this.jobParameters = Collections.unmodifiableMap(jobParameters);
        this.soapClient = soapClient;

        log.info("SoapGenericItemReader initialized for source '{}'", sourceConfig.getName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ItemStreamReader implementation
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void open(org.springframework.batch.item.ExecutionContext executionContext) {
        log.info("Opening SOAP reader for source '{}'", sourceConfig.getName());

        responseFetched = false;
        itemsRead = 0;
        buffer.clear();

        log.info("SOAP reader opened - endpoint: {}, SOAPAction: {}", 
                 soapConfig.getEndpoint(), soapConfig.getSoapAction());
    }

    @Override
    public GenericRecord read() throws Exception {
        // Fetch SOAP response on first read call (lazy loading)
        if (!responseFetched) {
            fetchSoapResponse();
            responseFetched = true;
        }

        // Poll next item from buffer
        GenericRecord record = buffer.poll();
        if (record != null) {
            itemsRead++;
        }

        return record;
    }

    @Override
    public void close() {
        log.info("Closing SOAP reader for source '{}' - total items read: {}", 
                 sourceConfig.getName(), itemsRead);
        buffer.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SOAP call and XML processing
    // ─────────────────────────────────────────────────────────────────────────

    private void fetchSoapResponse() throws Exception {
        log.debug("Preparing SOAP request for source '{}'", sourceConfig.getName());

        // Build SOAP request from template with bind variables
        String soapRequest = buildSoapRequest();
        log.debug("SOAP request built: {} characters", soapRequest.length());

        // Execute SOAP call
        String soapResponse = soapClient.call(soapRequest);
        log.debug("SOAP response received: {} characters", soapResponse.length());

        // Check for SOAP Fault (Q4: B - always fail on fault)
        if (containsSoapFault(soapResponse)) {
            String faultString = extractFaultString(soapResponse);
            throw new IllegalStateException(
                "SOAP Fault received from endpoint: " + faultString);
        }

        // Extract items using XPath
        List<GenericRecord> items = extractItems(soapResponse);
        log.info("Extracted {} items from SOAP response", items.size());

        buffer.addAll(items);
    }

    private String buildSoapRequest() {
        String template = soapConfig.getRequestTemplate();

        // Resolve each configured param's value up front, keyed by placeholder name
        Map<String, String> resolvedParams = new HashMap<>();
        for (Map.Entry<String, String> param : soapConfig.getRequestParams().entrySet()) {
            String paramName = param.getKey();
            String resolvedValue = resolveVariables(param.getValue(), "soap.requestParams." + paramName);
            resolvedParams.put(paramName, resolvedValue);
        }

        // Substitute :paramName tokens in a single word-bounded pass so a shorter
        // name that is a prefix of another (e.g. "status" / "statusCode") cannot
        // corrupt the longer placeholder, unlike a plain String.replace would.
        Matcher matcher = VariableResolver.BIND_PARAM_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String paramName = matcher.group(1).substring(1);
            String resolvedValue = resolvedParams.get(paramName);
            String replacement = resolvedValue != null ? escapeXml(resolvedValue) : matcher.group();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private boolean containsSoapFault(String soapResponse) {
        return soapResponse.contains("<soap:Fault") || 
               soapResponse.contains("<soapenv:Fault") ||
               soapResponse.contains("<SOAP-ENV:Fault");
    }

    private String extractFaultString(String soapResponse) {
        try {
            Document doc = parseXml(soapResponse);
            XPath xpath = XPathFactory.newInstance().newXPath();

            // Try different SOAP fault paths
            String faultString = (String) xpath.evaluate(
                "//faultstring/text() | //soap:faultstring/text() | //soapenv:faultstring/text()",
                doc, XPathConstants.STRING);

            if (faultString == null || faultString.isBlank()) {
                faultString = "Unknown SOAP Fault";
            }

            return faultString;
        } catch (Exception e) {
            return "Failed to parse SOAP Fault: " + e.getMessage();
        }
    }

    private List<GenericRecord> extractItems(String soapResponse) throws Exception {
        Document doc = parseXml(soapResponse);
        XPath xpath = XPathFactory.newInstance().newXPath();

        // Extract nodes matching dataPath
        NodeList nodes = (NodeList) xpath.evaluate(
            soapConfig.getDataPath(), doc, XPathConstants.NODESET);

        if (nodes == null || nodes.getLength() == 0) {
            log.warn("XPath '{}' returned 0 nodes", soapConfig.getDataPath());
            return Collections.emptyList();
        }

        log.debug("XPath matched {} nodes", nodes.getLength());

        // Convert each XML node to GenericRecord
        List<GenericRecord> records = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            GenericRecord record = convertNodeToRecord(node, xpath);
            records.add(record);
        }

        return records;
    }

    private GenericRecord convertNodeToRecord(Node node, XPath xpath) throws Exception {
        Map<String, Object> recordData = new HashMap<>();

        for (ColumnConfig column : sourceConfig.getColumns()) {
            String columnName = column.getName();
            String xpathExpr = column.getXpath();

            if (xpathExpr == null || xpathExpr.isBlank()) {
                // Default: ./columnName/text()
                xpathExpr = "./" + columnName + "/text()";
            }

            // Evaluate XPath relative to current node
            String value = (String) xpath.evaluate(xpathExpr, node, XPathConstants.STRING);

            // Type conversion
            Object convertedValue = convertValue(value, column);
            recordData.put(columnName, convertedValue);
        }

        return new GenericRecord(recordData);
    }

    private Object convertValue(String value, ColumnConfig column) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String type = column.getType().toUpperCase();

        try {
            switch (type) {
                case "STRING":
                    return value;

                case "INTEGER":
                case "LONG":
                    return Long.parseLong(value.trim());

                case "DECIMAL":
                case "DOUBLE":
                    return Double.parseDouble(value.trim());

                case "BOOLEAN":
                    return Boolean.parseBoolean(value.trim());

                case "DATE":
                    String format = column.getFormat();
                    if (format != null && !format.isBlank()) {
                        return LocalDate.parse(value.trim(), formatterFor(format));
                    }
                    return LocalDate.parse(value.trim());

                case "DATETIME":
                    String datetimeFormat = column.getFormat();
                    if (datetimeFormat != null && !datetimeFormat.isBlank()) {
                        return LocalDateTime.parse(value.trim(), formatterFor(datetimeFormat));
                    }
                    return LocalDateTime.parse(value.trim());

                default:
                    return value;
            }
        } catch (Exception e) {
            log.warn("Failed to convert value '{}' to type {} for column {}: {}",
                     value, type, column.getName(), e.getMessage());
            return value;
        }
    }

    private DateTimeFormatter formatterFor(String pattern) {
        return dateFormatters.computeIfAbsent(pattern, DateTimeFormatter::ofPattern);
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(soapConfig.isNamespaceAware());
        // Harden against XXE: SOAP messages must not contain a DTD (SOAP 1.1 §3,
        // SOAP 1.2 Part 1 §5), so a DOCTYPE in a response is always illegitimate
        // and rejecting it closes off external-entity file disclosure and SSRF.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Variable resolution
    // ─────────────────────────────────────────────────────────────────────────

    private String resolveVariables(String input, String context) {
        return VariableResolver.resolve(input, jobParameters, context);
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }

    /**
     * SOAP client interface for making SOAP calls.
     * Implemented by SoapGenericItemReaderBuilder.
     */
    public interface SoapClient {
        /**
         * Execute a SOAP call.
         *
         * @param soapRequest SOAP envelope XML
         * @return SOAP response XML
         * @throws Exception if the call fails
         */
        String call(String soapRequest) throws Exception;
    }
}
