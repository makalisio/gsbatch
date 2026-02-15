package org.makalisio.gsbatch.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Configuration du writer dans le YAML de la source.
 *
 * <p>Si absent du YAML, le framework cherche un bean nommé
 * {@code {sourceName}Writer} dans le contexte Spring (comportement historique).</p>
 *
 * <h2>Exemples YAML</h2>
 *
 * <p>Writer SQL (fichier INSERT) :</p>
 * <pre>
 * writer:
 *   type: SQL
 *   sqlDirectory: /opt/sql
 *   sqlFile: insert_orders.sql
 *   onError: SKIP
 *   skipLimit: 10
 * </pre>
 *
 * <p>Writer Java (bean Spring) :</p>
 * <pre>
 * writer:
 *   type: JAVA
 *   beanName: ordersWriter
 *   onError: FAIL
 * </pre>
 *
 * <p>Le fichier SQL utilise des bind variables dont les noms correspondent
 * aux champs du {@code GenericRecord} :</p>
 * <pre>
 *   INSERT INTO ORDERS_OUT (order_id, amount, currency)
 *   VALUES (:order_id, :amount, :currency)
 * </pre>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class WriterConfig {

    /**
     * Type de writer : {@code SQL} ou {@code JAVA}.
     *
     * <ul>
     *   <li>{@code SQL}  — exécute le fichier SQL en batch, bind variables = champs du {@code GenericRecord}</li>
     *   <li>{@code JAVA} — délègue au bean Spring nommé {@code beanName} (doit implémenter {@code ItemWriter})</li>
     * </ul>
     */
    private String type;

    /**
     * Répertoire contenant le fichier SQL (requis si {@code type=SQL}).
     */
    private String sqlDirectory;

    /**
     * Nom du fichier SQL dans {@code sqlDirectory} (requis si {@code type=SQL}).
     * Doit contenir une seule instruction INSERT/UPDATE/MERGE/DELETE.
     */
    private String sqlFile;

    /**
     * Nom du bean Spring {@code ItemWriter} (requis si {@code type=JAVA}).
     */
    private String beanName;

    /**
     * Bean name de la DataSource à utiliser (optionnel, multi-DB).
     */
    private String dataSourceBean;

    /**
     * Comportement en cas d'erreur sur une ligne.
     *
     * <ul>
     *   <li>{@code FAIL} (défaut) — le job échoue immédiatement au premier problème</li>
     *   <li>{@code SKIP} — la ligne est ignorée, l'erreur est loguée, le job continue</li>
     * </ul>
     */
    private String onError = "FAIL";

    /**
     * Nombre maximum de lignes ignorables (utilisé uniquement si {@code onError=SKIP}).
     * Si dépassé, le job échoue. Défaut : 10.
     */
    private int skipLimit = 10;

    /**
     * Indique si le writer est configuré en mode tolérant aux erreurs.
     *
     * @return {@code true} si {@code onError=SKIP}
     */
    public boolean isSkipOnError() {
        return "SKIP".equalsIgnoreCase(onError);
    }

    /**
     * Valide la configuration du writer.
     *
     * @throws IllegalStateException si la configuration est invalide
     */
    public void validate() {
        if (type == null || type.isBlank()) {
            throw new IllegalStateException(
                "writer.type est requis (SQL ou JAVA)");
        }

        if ("SQL".equalsIgnoreCase(type)) {
            if (sqlDirectory == null || sqlDirectory.isBlank()) {
                throw new IllegalStateException(
                    "writer.sqlDirectory est requis quand type=SQL");
            }
            if (sqlFile == null || sqlFile.isBlank()) {
                throw new IllegalStateException(
                    "writer.sqlFile est requis quand type=SQL");
            }
        } else if ("JAVA".equalsIgnoreCase(type)) {
            if (beanName == null || beanName.isBlank()) {
                throw new IllegalStateException(
                    "writer.beanName est requis quand type=JAVA");
            }
        } else {
            throw new IllegalStateException(
                "writer.type invalide : '" + type + "'. Valeurs acceptées : SQL, JAVA");
        }

        if (!"FAIL".equalsIgnoreCase(onError) && !"SKIP".equalsIgnoreCase(onError)) {
            throw new IllegalStateException(
                "writer.onError invalide : '" + onError + "'. Valeurs acceptées : FAIL, SKIP");
        }

        if (isSkipOnError() && skipLimit <= 0) {
            throw new IllegalStateException(
                "writer.skipLimit doit être > 0 quand onError=SKIP");
        }
    }
}
