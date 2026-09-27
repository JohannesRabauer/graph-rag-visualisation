package io.graphrag.core.usecase;

import io.graphrag.core.domain.ProjectionModel;

import java.util.List;

/**
 * Fits and applies a simple 2D PCA projection over embedding vectors, so a
 * corpus's chunk embeddings (and later, individual query embeddings) can be
 * placed into the same settled 2D scatter for visualization.
 */
final class TwoDProjection {

    private static final int ITERATIONS = 30;
    private static final double EPSILON = 1.0e-12;

    private TwoDProjection() {
    }

    /**
     * Fits a 2D PCA model over {@code vectors} and projects them with it in
     * one step — the batch-only convenience this class originally offered.
     */
    static double[][] project(List<float[]> vectors) {
        ProjectionModel model = fit(vectors);
        return projectAll(model, vectors);
    }

    /**
     * Fits (mean + first two principal components) but does not project —
     * kept and persisted so a later, single out-of-band vector (a query
     * embedding) can be placed into this same settled space via
     * {@link #project(ProjectionModel, float[])} without ever recomputing
     * or reshuffling the batch's own layout.
     */
    static ProjectionModel fit(List<float[]> vectors) {
        int n = vectors == null ? 0 : vectors.size();
        if (n == 0) {
            return new ProjectionModel(new double[0], new double[0], new double[0]);
        }

        int d = vectors.getFirst().length;
        double[] means = means(vectors, n, d);
        if (n == 1) {
            return new ProjectionModel(means, new double[d], new double[d]);
        }

        double[][] centered = centerWithMeans(vectors, means, n, d);
        double[] pc1 = principalComponent(centered, n, d, seed(d, 1));
        double[] scores1 = scores(centered, pc1, n, d);
        double[][] deflated = deflate(centered, pc1, scores1, n, d);
        double[] pc2 = principalComponent(deflated, n, d, seed(d, 2));
        return new ProjectionModel(means, pc1, pc2);
    }

    /** Applies an already-fitted model to a batch of vectors. */
    static double[][] projectAll(ProjectionModel model, List<float[]> vectors) {
        int n = vectors == null ? 0 : vectors.size();
        double[][] result = new double[n][2];
        for (int i = 0; i < n; i++) {
            result[i] = project(model, vectors.get(i));
        }
        return result;
    }

    /**
     * Applies an already-fitted model to a single vector — the mechanism a
     * query embedding uses to land in the corpus's settled 2D scatter.
     * Degrades to {@code {0.0, 0.0}} instead of NaN/Infinity, same as the
     * batch path.
     */
    static double[] project(ProjectionModel model, float[] vector) {
        if (model == null || vector == null || model.mean().length == 0) {
            return new double[]{0.0, 0.0};
        }
        double[] mean = model.mean();
        int d = Math.min(vector.length, mean.length);
        double[] centered = new double[d];
        for (int j = 0; j < d; j++) {
            centered[j] = vector[j] - mean[j];
        }
        return new double[]{
                componentScore(centered, model.pc1(), d),
                componentScore(centered, model.pc2(), d)
        };
    }

    private static double componentScore(double[] centered, double[] component, int d) {
        if (component == null || component.length == 0 || norm(component) <= EPSILON) {
            return 0.0;
        }
        double sum = 0.0;
        for (int j = 0; j < d; j++) {
            sum += centered[j] * component[j];
        }
        return Double.isFinite(sum) ? sum : 0.0;
    }

    private static double[] means(List<float[]> vectors, int n, int d) {
        double[] means = new double[d];
        for (float[] vector : vectors) {
            for (int j = 0; j < d; j++) {
                means[j] += vector[j];
            }
        }
        for (int j = 0; j < d; j++) {
            means[j] /= n;
        }
        return means;
    }

    private static double[][] centerWithMeans(List<float[]> vectors, double[] means, int n, int d) {
        double[][] centered = new double[n][d];
        for (int i = 0; i < n; i++) {
            float[] vector = vectors.get(i);
            for (int j = 0; j < d; j++) {
                centered[i][j] = vector[j] - means[j];
            }
        }
        return centered;
    }

    private static double[] principalComponent(double[][] matrix, int n, int d, double[] seed) {
        if (d == 0) {
            return new double[0];
        }

        double[] v = seed.clone();
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            double[] u = new double[n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < d; j++) {
                    u[i] += matrix[i][j] * v[j];
                }
            }

            double[] vNew = new double[d];
            for (int j = 0; j < d; j++) {
                for (int i = 0; i < n; i++) {
                    vNew[j] += matrix[i][j] * u[i];
                }
            }

            double norm = norm(vNew);
            if (!Double.isFinite(norm) || norm <= EPSILON) {
                return new double[d];
            }
            for (int j = 0; j < d; j++) {
                v[j] = vNew[j] / norm;
            }
        }
        return v;
    }

    private static double[] seed(int dimensions, int which) {
        double[] seed = new double[dimensions];
        if (dimensions == 0) {
            return seed;
        }
        for (int i = 0; i < dimensions; i++) {
            seed[i] = which == 1 ? 1.0 : (i % 2 == 0 ? 1.0 : -1.0);
        }
        double norm = norm(seed);
        if (norm <= EPSILON) {
            return seed;
        }
        for (int i = 0; i < dimensions; i++) {
            seed[i] /= norm;
        }
        return seed;
    }

    private static double[] scores(double[][] matrix, double[] component, int n, int d) {
        double[] scores = new double[n];
        if (norm(component) <= EPSILON) {
            return scores;
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                scores[i] += matrix[i][j] * component[j];
            }
        }
        return scores;
    }

    private static double[][] deflate(double[][] matrix, double[] component, double[] scores, int n, int d) {
        double[][] deflated = new double[n][d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                deflated[i][j] = matrix[i][j] - (scores[i] * component[j]);
            }
        }
        return deflated;
    }

    private static double norm(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }
}
