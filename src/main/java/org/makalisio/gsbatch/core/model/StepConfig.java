package org.makalisio.gsbatch.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Configuration d'une step de pre/post processing (type Tasklet).
 *
 * <p>Utilisé pour les steps {@code preprocessing} et {@code postprocessing}
 * dans le fichier YAML de la source. La step est ignorée si {@code enabled=false}.</p>
 *
 * <h2>Exemple YAML</h2>
 * <pre>
 * preprocessing:
 *   enabled: true
 *   type: SQL
 *   sqlDirectory: /opt/sql
 *   sqlFile: pre_orders.sql
 *
 * postprocessing:
 *   enabled: true
 *   type: JAVA
 *   beanName: ordersPostprocessor
 * </pre>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class StepConfig {

    /**
     * Active ou désactive cette step.
     * Si {@code false}, la step s'exécute mais ne fait rien (no-op).
     * Défaut : {@code false}.
     */
    private boolean enabled = false;

    /**
     * Type d'exécution : {@code SQL} ou {@code JAVA}.
     *
     * <ul>
     *   <li>{@code SQL}  — exécute le fichier SQL dans {@code sqlDirectory/sqlFile}</li>
     *   <li>{@code JAVA} — délègue au bean Spring nommé {@code beanName} (doit implémenter {@code Tasklet})</li>
     * </ul>
     */
    private String type;

    /**
     * Répertoire contenant le fichier SQL (requis si {@code type=SQL}).
     * Peut être absolu ou relatif au répertoire de lancement.
     */
    private String sqlDirectory;

    /**
     * Nom du fichier SQL dans {@code sqlDirectory} (requis si {@code type=SQL}).
     *
     * <p>Le fichier peut contenir plusieurs instructions séparées par {@code ;},
     * toutes exécutées dans la même transaction.</p>
     *
     * <p>Les bind variables {@code :paramName} sont résolues depuis les {@code jobParameters}.</p>
     */
    private String sqlFile;

    /**
     * Nom du bean Spring à appeler (requis si {@code type=JAVA}).
     * Le bean doit implémenter {@code org.springframework.batch.core.step.tasklet.Tasklet}.
     */
    private String beanName;

    /**
     * Bean name de la DataSource à utiliser (optionnel, multi-DB).
     * Utilise la DataSource principale si absent.
     */
    private String dataSourceBean;

    /**
     * Valide la configuration de cette step.
     *
     * @param stepName nom de la step pour les messages d'erreur ("preprocessing" ou "postprocessing")
     * @throws IllegalStateException si la configuration est invalide
     */
    public void validate(String stepName) {
        if (!enabled) return;

        if (type == null || type.isBlank()) {
            throw new IllegalStateException(
                stepName + ".type est requis (SQL ou JAVA)");
        }

        if ("SQL".equalsIgnoreCase(type)) {
            if (sqlDirectory == null || sqlDirectory.isBlank()) {
                throw new IllegalStateException(
                    stepName + ".sqlDirectory est requis quand type=SQL");
            }
            if (sqlFile == null || sqlFile.isBlank()) {
                throw new IllegalStateException(
                    stepName + ".sqlFile est requis quand type=SQL");
            }
        } else if ("JAVA".equalsIgnoreCase(type)) {
            if (beanName == null || beanName.isBlank()) {
                throw new IllegalStateException(
                    stepName + ".beanName est requis quand type=JAVA");
            }
        } else {
            throw new IllegalStateException(
                stepName + ".type invalide : '" + type + "'. Valeurs acceptées : SQL, JAVA");
        }
    }
}
