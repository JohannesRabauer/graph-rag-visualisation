package com.graphraglens.web;

import com.graphraglens.core.domain.Corpus;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the Acceptance Criteria of Story 2.1 for {@code POST /api/corpora}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorpusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CorpusStore corpusStore;

    @MockBean
    private CorpusIngestionOrchestrator ingestionOrchestrator;

    @Test
    void uploadingATxtFileReturns201WithCorpusIdAndDocumentNamesAndRegistersTheCorpus() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "test.txt", "text/plain", "hello world".getBytes(StandardCharsets.UTF_8));

        String responseBody = mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corpusId").exists())
                .andExpect(jsonPath("$.documentNames[0]").value("test.txt"))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(responseBody, "$.corpusId");
        Optional<Corpus> stored = corpusStore.get(corpusId);

        assertThat(stored).isPresent();
        assertThat(stored.get().documentNames()).containsExactly("test.txt");
        assertThat(stored.get().documents().get(0).content()).isEqualTo("hello world");
        verify(ingestionOrchestrator).start(any(Corpus.class));
    }

    @Test
    void uploadingMultipleTxtFilesRegistersAllOfThemOnOneCorpus() throws Exception {
        MockMultipartFile a = new MockMultipartFile("files", "a.txt", "text/plain", "a".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile b = new MockMultipartFile("files", "b.txt", "text/plain", "b".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/corpora").file(a).file(b))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentNames[0]").value("a.txt"))
                .andExpect(jsonPath("$.documentNames[1]").value("b.txt"))
                .andExpect(jsonPath("$.documentCount").value(2));
    }

    @Test
    void uploadingAnUnsupportedFileTypeReturns400WithAPlainLanguageErrorNamingTheFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "test.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "not supported".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("test.docx")));
    }

    @Test
    void uploadingNoFilesReturns400WithANoFilesProvidedError() throws Exception {
        mockMvc.perform(multipart("/api/corpora"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("No files were provided."));
    }

    @Test
    void uploadingAMixOfSupportedAndUnsupportedFilesReturns400AndRegistersNoCorpus() throws Exception {
        int sizeBefore = corpusStore.size();
        MockMultipartFile valid = new MockMultipartFile(
                "files", "ok.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile invalid = new MockMultipartFile(
                "files", "bad.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "nope".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/corpora").file(valid).file(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bad.docx")));

        assertThat(corpusStore.size()).isEqualTo(sizeBefore);
    }

    @Test
    void uploadingAPdfFileReturns201AndRegistersCorpus() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile(
                "files", "test.pdf", "application/pdf", createPdfWithText("hello from pdf"));

        String responseBody = mockMvc.perform(multipart("/api/corpora").file(pdf))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentNames[0]").value("test.pdf"))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(responseBody, "$.corpusId");
        Optional<Corpus> stored = corpusStore.get(corpusId);
        assertThat(stored).isPresent();
        assertThat(stored.get().documents().get(0).content()).contains("hello from pdf");
    }

    @Test
    void uploadingAPdfWithNoExtractableTextReturns400AndRegistersNoCorpus() throws Exception {
        int sizeBefore = corpusStore.size();
        MockMultipartFile pdf = new MockMultipartFile(
                "files", "empty.pdf", "application/pdf", createPdfWithoutText());

        mockMvc.perform(multipart("/api/corpora").file(pdf))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("empty.pdf")));

        assertThat(corpusStore.size()).isEqualTo(sizeBefore);
    }

    @Test
    void uploadingAFileThatFailsToReadReturns500WithAGenericPlainLanguageError() throws Exception {
        MockMultipartFile file = new ThrowingMultipartFile("files", "broken.txt", "text/plain");

        mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Failed to read an uploaded file. Please try again."));
    }

    @Test
    void demoDatasetEndpointReturnsCreatedCorpusWithDisplayName() throws Exception {
        String responseBody = mockMvc.perform(post("/api/corpora/demo"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Sherlock Holmes — Demo Dataset"))
                .andExpect(jsonPath("$.corpusId").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(responseBody, "$.corpusId");
        assertThat(corpusStore.get(corpusId)).isPresent();
        verify(ingestionOrchestrator).start(any(Corpus.class));
    }

    @Test
    void progressEndpointExposesSseStream() throws Exception {
        mockMvc.perform(get("/api/corpora/test-corpus/progress"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"));
    }

    /**
     * A {@link MockMultipartFile} whose {@code getBytes()} always throws, to
     * exercise {@code CorpusController}'s {@code IOException} handling
     * without needing a real broken stream. Flows through {@code MockMvc}'s
     * multipart request unchanged (no re-parsing), so it reaches the
     * controller exactly as constructed here.
     */
    private static final class ThrowingMultipartFile extends MockMultipartFile {

        ThrowingMultipartFile(String name, String originalFilename, String contentType) {
            super(name, originalFilename, contentType, new byte[0]);
        }

        @Override
        public byte[] getBytes() throws IOException {
            throw new IOException("Simulated read failure");
        }
    }

    private static byte[] createPdfWithText(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(100, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] createPdfWithoutText() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }
}
