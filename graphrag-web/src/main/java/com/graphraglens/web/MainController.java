package com.graphraglens.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the main screen (Story 1.3). Renders the resting/idle-state
 * canvas — no chat panel, upload, or ingestion UI wired yet; those
 * arrive in later epics.
 */
@Controller
public class MainController {

    @GetMapping("/")
    public String index() {
        return "index";
    }
}
