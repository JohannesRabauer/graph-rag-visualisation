package com.graphraglens.web;

import com.graphraglens.core.domain.UploadedFile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads bundled Sherlock Holmes demo corpus files from classpath.
 */
@Component
public class DemoDatasetService {

    public static final String DEMO_DATASET_DISPLAY_NAME = "Sherlock Holmes — Demo Dataset";

    public List<UploadedFile> loadDemoCorpus() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:/demo-data/sherlock/*.txt");
            List<UploadedFile> files = new ArrayList<>();
            for (Resource resource : resources) {
                files.add(new UploadedFile(resource.getFilename(), resource.getContentAsByteArray()));
            }
            if (files.isEmpty()) {
                throw new IllegalStateException("Demo dataset files are missing.");
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load demo dataset resources.", e);
        }
    }
}
