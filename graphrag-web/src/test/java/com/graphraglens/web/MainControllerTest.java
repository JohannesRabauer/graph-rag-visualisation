package com.graphraglens.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the four Acceptance Criteria of Story 1.3 for {@code GET /}.
 */
@WebMvcTest(MainController.class)
class MainControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rendersTheRestingStateWithExactCopyAndNoLaterEpicUi() throws Exception {
        String body = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("Knowledge Graph — Resting");
        assertThat(body).contains(
                "Watch a Knowledge Graph get built, clustered, and searched — the mechanics most GraphRAG tools keep hidden.");
        assertThat(body).contains("instrument.css");

        String lowerCaseBody = body.toLowerCase();
        assertThat(lowerCaseBody).doesNotContain("chat");
        assertThat(lowerCaseBody).doesNotContain("composer");
        assertThat(lowerCaseBody).doesNotContain("upload");
        assertThat(lowerCaseBody).doesNotContain("cytoscape");
        assertThat(lowerCaseBody).doesNotContain("<script");
    }
}
