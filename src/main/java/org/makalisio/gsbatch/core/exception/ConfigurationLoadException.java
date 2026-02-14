// org.makalisio.gsbatch.core.exception.ConfigurationLoadException
package org.makalisio.gsbatch.core.exception;

/**
 * Exception thrown when a configuration file cannot be loaded or parsed.
 * 
 * @author Makalisio
 * @since 0.0.1
 */
public class ConfigurationLoadException extends RuntimeException {

    /**
     * Constructs a new ConfigurationLoadException with the specified detail message.
     * 
     * @param message the detail message
     */
    public ConfigurationLoadException(String message) {
        super(message);
    }

    /**
     * Constructs a new ConfigurationLoadException with the specified detail message and cause.
     * 
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public ConfigurationLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
