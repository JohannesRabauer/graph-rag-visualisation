package com.graphraglens.web.config;

import com.graphraglens.adapter.parsing.PdfDocumentParserAdapter;
import com.graphraglens.adapter.parsing.PlainTextDocumentParserAdapter;
import com.graphraglens.core.port.DocumentParserPort;
import com.graphraglens.core.usecase.IngestCorpus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the framework-free parsing adapter and use case into Spring.
 *
 * <p>{@code graphrag-adapter-parsing} stays free of any Spring dependency
 * (AD-1) — this {@code @Configuration} class, not a {@code @Component} in
 * the adapter module, is what makes {@link PlainTextDocumentParserAdapter}
 * a bean. Spring auto-collects every {@link DocumentParserPort} bean into a
 * {@code List<DocumentParserPort>}, which is then injected into {@link
 * IngestCorpus} — no {@code @Qualifier}-based type dispatch is needed in
 * {@code graphrag-web} (AD-9).
 */
@Configuration
public class ParserConfig {

    @Bean
    public DocumentParserPort plainTextDocumentParserAdapter() {
        return new PlainTextDocumentParserAdapter();
    }

    @Bean
    public DocumentParserPort pdfDocumentParserAdapter() {
        return new PdfDocumentParserAdapter();
    }

    @Bean
    public IngestCorpus ingestCorpus(List<DocumentParserPort> documentParsers) {
        return new IngestCorpus(documentParsers);
    }
}
