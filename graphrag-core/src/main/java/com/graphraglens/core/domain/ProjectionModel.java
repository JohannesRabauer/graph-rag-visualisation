package com.graphraglens.core.domain;

/**
 * A fitted 2D PCA projection basis for a corpus's chunk embeddings — the
 * mean vector and the two principal-component axes computed once, at
 * ingestion time, over every chunk embedding in the corpus.
 *
 * <p>Persisting this model (rather than only the resulting per-chunk
 * projections) lets a later, out-of-band vector — such as a query's own
 * embedding — be placed into that same settled 2D space without ever
 * recomputing or reshuffling the corpus's own chunk layout (Story 8.5).
 */
public record ProjectionModel(double[] mean, double[] pc1, double[] pc2) {

    public ProjectionModel {
        mean = mean == null ? new double[0] : mean.clone();
        pc1 = pc1 == null ? new double[0] : pc1.clone();
        pc2 = pc2 == null ? new double[0] : pc2.clone();
    }

    @Override
    public double[] mean() {
        return mean.clone();
    }

    @Override
    public double[] pc1() {
        return pc1.clone();
    }

    @Override
    public double[] pc2() {
        return pc2.clone();
    }
}
