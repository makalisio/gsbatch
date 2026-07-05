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
package org.makalisio.gsbatch.core.diagnostics;

/**
 * Reports exactly which reader/processor/writer/tasklet mechanism a given
 * source resolves to, without running the job - answers "which bean will
 * actually be used for source X?" without having to read the factory code.
 *
 * @param sourceName      the source name that was described
 * @param sourceType      raw {@code SourceConfig.type} value (e.g. "REST")
 * @param reader          which reader builder this source dispatches to
 * @param processor       which processor bean (or pass-through) will run
 * @param writer          which writer (SQL file or bean) will run
 * @param preprocessing   resolution of the preprocessing step
 * @param postprocessing  resolution of the postprocessing step
 * @author Makalisio
 * @since 0.0.1
 */
public record SourceResolutionReport(
        String sourceName,
        String sourceType,
        Resolution reader,
        Resolution processor,
        Resolution writer,
        Resolution preprocessing,
        Resolution postprocessing
) {

    /**
     * One resolved component of the report.
     *
     * @param resolvable  {@code true} if this would actually work if the job ran now
     * @param description human-readable explanation (bean name, SQL file path, or why it fails)
     */
    public record Resolution(boolean resolvable, String description) {

        static Resolution resolved(String description) {
            return new Resolution(true, description);
        }

        static Resolution unresolvable(String description) {
            return new Resolution(false, description);
        }

        @Override
        public String toString() {
            return (resolvable ? "OK" : "FAIL") + " - " + description;
        }
    }

    /**
     * @return {@code true} if every component of this report is resolvable
     */
    public boolean isFullyResolvable() {
        return reader.resolvable() && processor.resolvable() && writer.resolvable()
                && preprocessing.resolvable() && postprocessing.resolvable();
    }

    /**
     * @return a multi-line, human-readable rendering of this report
     */
    public String format() {
        return """
                Source '%s' (type=%s):
                  reader:         %s
                  processor:      %s
                  writer:         %s
                  preprocessing:  %s
                  postprocessing: %s""".formatted(
                sourceName, sourceType, reader, processor, writer, preprocessing, postprocessing);
    }
}
