package dev.rabauer.graphrag.web;

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
        assertThat(body).contains("Drift Search");
        assertThat(body).contains("cytoscape@3.28.1");
        assertThat(body).contains("id=\"graph-canvas\"");
        assertThat(body).contains("id=\"graph-legend\"");
        assertThat(body).contains("id=\"workflow-status\"");
        assertThat(body).contains("id=\"workflow-status-text\"");
    }

    @Test
    void rendersTheReplayScrubberMarkupAndScript() throws Exception {
        String body = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("id=\"graph-eyebrow\"");
        assertThat(body).contains("id=\"replay-scrubber\"");
        assertThat(body).contains("id=\"replay-close\"");
        assertThat(body).contains("id=\"replay-step-back\"");
        assertThat(body).contains("id=\"replay-play-pause\"");
        assertThat(body).contains("id=\"replay-step-forward\"");
        assertThat(body).contains("id=\"replay-tick-track\"");
        assertThat(body).contains("id=\"replay-step-counter\"");
        assertThat(body).contains("id=\"replay-caption\"");
        assertThat(body).contains("replay.js");
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
        assertThat(body).contains("id=\"workflow-retry-button\"");
        assertThat(body).contains("id=\"workflow-restart-button\"");
        assertThat(body).contains("upload.js");
    }

    @Test
    void rendersTheOfflineDemoOptionAndTheStateDurabilityNote() throws Exception {
        String body = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("id=\"demo-offline-button\"");
        assertThat(body).contains("Use the Offline Demo");
        assertThat(body).contains("id=\"composer-offline-note\"");
        assertThat(body).contains("state-durability-note");
        assertThat(body).contains("restarting it clears them");
    }

    @Test
    void rendersTheEntitySearchControl() throws Exception {
        String body = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("id=\"entity-search\"");
        assertThat(body).contains("id=\"entity-search-input\"");
        assertThat(body).contains("id=\"entity-search-results\"");
        assertThat(body).contains("entity-search.js");
    }

    @Test
    void mainScreenHasNoSeparateExplorePageLeftToLinkTo() throws Exception {
        String body = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The former separate Explore page (Story 6.1) was merged onto this
        // screen's own canvas (2026-09-20 UX pass) — no second page exists
        // to link to any more.
        assertThat(body).doesNotContain("href=\"/explore\"");
    }

    @Test
    void mainScreenRendersTheEntityDetailPanelMergedFromTheFormerExplorePage() throws Exception {
        String body = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("id=\"entity-detail-panel\"");
        assertThat(body).contains("id=\"entity-detail-close\"");
        assertThat(body).contains("id=\"entity-detail-name\"");
        assertThat(body).contains("id=\"entity-detail-type\"");
        assertThat(body).contains("id=\"entity-detail-relationships\"");
        assertThat(body).contains("id=\"entity-detail-tags\"");
    }

    @Test
    void getExploreNoLongerHasARoute() throws Exception {
        mockMvc.perform(get("/explore")).andExpect(status().isNotFound());
    }
}
