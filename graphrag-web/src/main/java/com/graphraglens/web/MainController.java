package com.graphraglens.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the main screen (Story 1.3) and the Explore page (Story 6.1) — a
 * dedicated, always-available view of the full Knowledge Graph, independent
 * of any query or in-flight ingestion run.
 */
@Controller
public class MainController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/explore")
    public String explore() {
        return "explore";
    }
}
