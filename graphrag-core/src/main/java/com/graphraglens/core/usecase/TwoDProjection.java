package com.graphraglens.core.usecase;

import java.util.List;

final class TwoDProjection {

    private static final int ITERATIONS = 30;
    private static final double EPSILON = 1.0e-12;

    private TwoDProjection() {
    }

    static double[][] project(List<float[]> vectors) {
        int n = vectors == null ? 0 : vectors.size();
        if (n == 0) {
            return new double[0][2];
        }
        if (n == 1) {
            return new double[][]{{0.0, 0.0}};
        }

        int d = vectors.getFirst().length;
        double[][] centered = center(vectors, n, d);
        double[] pc1 = principalComponent(centered, n, d, seed(d, 1));
        double[] scores1 = scores(centered, pc1, n, d);
        double[][] deflated = deflate(centered, pc1, scores1, n, d);
        double[] pc2 = principalComponent(deflated, n, d, seed(d, 2));
        double[] scores2 = scores(deflated, pc2, n, d);

        double[][] result = new double[n][2];
        for (int i = 0; i < n; i++) {
            result[i] = new double[]{scores1[i], scores2[i]};
        }
        return result;
    }

    private static double[][] center(List<float[]> vectors, int n, int d) {
        double[] means = new double[d];
        for (float[] vector : vectors) {
            for (int j = 0; j < d; j++) {
                means[j] += vector[j];
            }
        }
        for (int j = 0; j < d; j++) {
            means[j] /= n;
        }

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
