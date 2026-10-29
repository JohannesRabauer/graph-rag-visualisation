package dev.rabauer.graphrag.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.core.domain.UnreadableDocumentException;
import dev.rabauer.graphrag.core.port.DocumentParserPort;
import dev.rabauer.graphrag.core.usecase.ConstructVectorIndex;
import dev.rabauer.graphrag.core.usecase.IngestCorpus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Upload limits and upload errors, without Docker: standalone MockMvc with test doubles. */
class UploadErrorsTest {

    @RestController
    static class OversizedUploadController {
        @PostMapping("/oversized")
        void upload() {
            // What Spring raises while resolving a multipart request over the limit.
            throw new MaxUploadSizeExceededException(DataSize.ofMegabytes(10).toBytes());
        }
    }

    @Configuration
    @EnableConfigurationProperties(MultipartProperties.class)
    static class PropertiesConfig {
    }

    private static MultipartProperties limits() {
        MultipartProperties properties = new MultipartProperties();
        properties.setMaxFileSize(DataSize.ofMegabytes(10));
        properties.setMaxRequestSize(DataSize.ofMegabytes(25));
        return properties;
    }

    @Test
    void anUploadOverTheLimitIsAnswered413WithAProblemDetail() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new OversizedUploadController())
                .setControllerAdvice(new UploadErrorAdvice(limits()))
                .build();

        mockMvc.perform(multipart("/oversized"))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.title").value("Upload too large"))
                .andExpect(jsonPath("$.detail").value(
                        "The upload is too large. Each file may be at most 10 MB and one upload at most 25 MB."));
    }

    @Test
    void anUnsupportedFileTypeIsAnswered415WithAProblemDetailNamingTheFile() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(List.of())).build();
        MockMultipartFile file = new MockMultipartFile("files", "slides.pptx",
                "application/octet-stream", "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.title").value("Unsupported file type"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("slides.pptx")));
    }

    @Test
    void anUnreadableDocumentStillAnswers400WithTheErrorShape() throws Exception {
        DocumentParserPort failing = new DocumentParserPort() {
            @Override
            public boolean supports(String filename) {
                return true;
            }

            @Override
            public String extract(String filename, byte[] content) {
                throw new UnreadableDocumentException(filename, "broken");
            }
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(List.of(failing))).build();
        MockMultipartFile file = new MockMultipartFile("files", "a.pdf", "application/pdf",
                "x".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void applicationYmlSetsExplicitMultipartLimits() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesConfig.class)
                .run(context -> {
                    MultipartProperties properties = context.getBean(MultipartProperties.class);
                    assertThat(properties.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(10));
                    assertThat(properties.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(25));
                });
    }

    private static CorpusIngestController controller(List<DocumentParserPort> parsers) {
        return new CorpusIngestController(mock(IngestCorpus.class), mock(Neo4jCorpusRegistry.class), parsers, null,
                null, null, null, mock(ConstructVectorIndex.class), null, null);
    }
}
