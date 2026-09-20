package com.graphraglens.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the main screen (Story 1.3). The former separate Explore page
 * (Story 6.1) was merged into this single screen (2026-09-20 UX pass) — the
 * same canvas is now always pannable/zoomable with a node-click detail
 * panel, rather than a second page duplicating a slightly more capable
 * version of the same view.
 */
@Controller
public class MainController {

    @GetMapping("/")
    public String index() {
        return "index";
    }
}
