package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class DemoDatasetService {

    public Corpus createSherlockCorpus() {
        return new Corpus(UUID.randomUUID().toString(), sherlockDocuments(), "Sherlock Holmes — Demo Dataset");
    }

    /**
     * Same underlying text as {@link #createSherlockCorpus()}, named
     * distinctly so the app bar's Corpus chip can never be mistaken for a
     * live run (Story 9.1) — the caller is responsible for actually routing
     * its construction through offline-only ports.
     */
    public Corpus createOfflineSherlockCorpus() {
        return new Corpus(UUID.randomUUID().toString(), sherlockDocuments(),
                "Sherlock Holmes — Offline Demo (no API calls)");
    }

    private List<UploadedDocument> sherlockDocuments() {
        return List.of(
                new UploadedDocument("A Scandal in Bohemia.txt",
                        "Irene Adler, professional adventuress, had outwitted Sherlock Holmes by stealing the photograph and turning the King's leverage against him. Holmes admired her ingenuity and recognized that she had triumphed by thinking several moves ahead."),
                new UploadedDocument("The Adventure of the Speckled Band.txt",
                        "A hidden clue in a locked room, a fluttering band of speckled silk, and a sudden death in a chamber of silence. Holmes followed the trail of motive, means, and opportunity to expose the murderer."),
                new UploadedDocument("The Final Problem.txt",
                        "Professor Moriarty had become the intellectual rival Holmes feared most: a criminal mastermind whose reach extended across Europe. The case turned on deduction, timing, and the willingness to face a dangerous adversary without hesitation."));
    }
}
