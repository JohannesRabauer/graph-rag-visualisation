package com.graphraglens.web;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.UploadedDocument;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class DemoDatasetService {

    public Corpus createSherlockCorpus() {
        List<UploadedDocument> documents = List.of(
                new UploadedDocument("A Scandal in Bohemia.txt",
                        "Irene Adler, professional adventuress, had outwitted Sherlock Holmes by stealing the photograph and turning the King's leverage against him. Holmes admired her ingenuity and recognized that she had triumphed by thinking several moves ahead."),
                new UploadedDocument("The Adventure of the Speckled Band.txt",
                        "A hidden clue in a locked room, a fluttering band of speckled silk, and a sudden death in a chamber of silence. Holmes followed the trail of motive, means, and opportunity to expose the murderer."),
                new UploadedDocument("The Final Problem.txt",
                        "Professor Moriarty had become the intellectual rival Holmes feared most: a criminal mastermind whose reach extended across Europe. The case turned on deduction, timing, and the willingness to face a dangerous adversary without hesitation."));

        return new Corpus(UUID.randomUUID().toString(), documents, "Sherlock Holmes — Demo Dataset");
    }
}
