package com.graphraglens.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the Acceptance Criteria for the resting state and the chat UI shell.
 */
@WebMvcTest(MainController.class)
class MainControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rendersTheRestingStateAndTheQuestionChatShell() throws Exception {
        String body = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("Knowledge Graph — Resting");
        assertThat(body).contains(
                "Watch a Knowledge Graph get built, clustered, and searched — the mechanics most GraphRAG tools keep hidden.");
        assertThat(body).contains("instrument.css");
        assertThat(body).contains("id=\"chat-panel\"");
        assertThat(body).contains("Local Search");
        assertThat(body).contains("Global Search");
        assertThat(body).doesNotContain("cytoscape");
    }

    @Test
    void rendersTheUploadControlAndHiddenCorpusChipAndErrorBannerSlots() throws Exception {
        String body = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("type=\"file\"");
        assertThat(body).contains("accept=\".txt,.pdf\"");
        assertThat(body).contains("multiple");
        assertThat(body).contains("id=\"corpus-chip\"");
        assertThat(body).contains("id=\"error-banner\"");
        assertThat(body).contains("Use the built-in Sherlock Holmes Demo Dataset");
        assertThat(body).contains("id=\"demo-dataset-button\"");
        assertThat(body).contains("upload.js");
    }
}
