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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the corpus-upload acceptance criteria for plain-text and PDF uploads.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorpusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CorpusStore corpusStore;

    @Test
    void progressEndpointStreamsHeartbeatEventsWithNamedEventEnvelope() throws Exception {
        mockMvc.perform(get("/api/corpora/progress-test/progress"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:heartbeat")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"type\":\"heartbeat\"")));
    }

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
    }

    @Test
    void uploadingAPdfFileReturns201WithExtractedTextAndRegistersTheCorpus() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "report.pdf", "application/pdf", createPdfBytes("A PDF report for testing."));

        String responseBody = mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentNames[0]").value("report.pdf"))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(responseBody, "$.corpusId");
        Optional<Corpus> stored = corpusStore.get(corpusId);

        assertThat(stored).isPresent();
        assertThat(stored.get().documents().get(0).content()).contains("A PDF report for testing.");
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
    void usingTheBuiltInDemoDatasetReturns201AndRegistersTheCorpus() throws Exception {
        String responseBody = mockMvc.perform(multipart("/api/corpora/demo"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sherlock Holmes — Demo Dataset"))
                .andExpect(jsonPath("$.documentCount").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(responseBody, "$.corpusId");
        Optional<Corpus> stored = corpusStore.get(corpusId);

        assertThat(stored).isPresent();
        assertThat(stored.get().name()).isEqualTo("Sherlock Holmes — Demo Dataset");
        assertThat(stored.get().documentNames()).containsExactly(
                "A Scandal in Bohemia.txt",
                "The Adventure of the Speckled Band.txt",
                "The Final Problem.txt");
    }

    @Test
    void submittingAQuestionReturnsAnAnswerAndTraceIdsForTheCorpus() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "sherlock.txt", "text/plain",
                "Irene Adler outwitted Sherlock Holmes by stealing the photograph. Holmes admired her ingenuity.".getBytes(StandardCharsets.UTF_8));

        String uploadBody = mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String corpusId = JsonPath.read(uploadBody, "$.corpusId");

        mockMvc.perform(post("/api/corpora/{corpusId}/query", corpusId)
                        .contentType("application/json")
                        .content("{\"question\":\"Why was Irene Adler able to outwit Holmes?\",\"mode\":\"LOCAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerId").exists())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.mode").value("LOCAL"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("Irene Adler")));
    }

    @Test
    void uploadingAnUnsupportedFileTypeReturns400WithAPlainLanguageErrorNamingTheFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "test.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "not really a docx".getBytes(StandardCharsets.UTF_8));

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
                "files", "bad.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "nope".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/corpora").file(valid).file(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bad.docx")));

        assertThat(corpusStore.size()).isEqualTo(sizeBefore);
    }

    @Test
    void uploadingAFileThatFailsToReadReturns500WithAGenericPlainLanguageError() throws Exception {
        MockMultipartFile file = new ThrowingMultipartFile("files", "broken.txt", "text/plain");

        mockMvc.perform(multipart("/api/corpora").file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Failed to read an uploaded file. Please try again."));
    }

    private static byte[] createPdfBytes(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(100, 700);
                contentStream.showText(text);
                contentStream.endText();
            }

            document.save(outputStream);
            return outputStream.toByteArray();
        }
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
}
