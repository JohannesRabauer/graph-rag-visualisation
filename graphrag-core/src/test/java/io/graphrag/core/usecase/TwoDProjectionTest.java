package io.graphrag.core.usecase;

import io.graphrag.core.domain.ProjectionModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoDProjectionTest {

    @Test
    void returnsAnEmptyProjectionForEmptyInput() {
        double[][] result = TwoDProjection.project(List.of());

        assertEquals(0, result.length);
    }

    @Test
    void projectsASingleVectorToTheOrigin() {
        double[][] result = TwoDProjection.project(List.of(new float[]{1.0f, 2.0f}));

        assertEquals(1, result.length);
        assertEquals(0.0, result[0][0]);
        assertEquals(0.0, result[0][1]);
    }

    @Test
    void separatesPointsAlongThePrincipalAxisWithoutDependingOnComponentSign() {
        double[][] result = TwoDProjection.project(List.of(
                new float[]{1.0f, 0.0f},
                new float[]{3.0f, 0.0f},
                new float[]{5.0f, 0.0f}));

        assertEquals(3, result.length);
        assertTrue(Math.abs(result[1][0]) < 1.0e-9);
        assertTrue(result[0][0] * result[2][0] < 0.0);
        assertTrue(Math.abs(result[0][1]) < 1.0e-9);
        assertTrue(Math.abs(result[1][1]) < 1.0e-9);
        assertTrue(Math.abs(result[2][1]) < 1.0e-9);
    }

    @Test
    void returnsZeroProjectionsForIdenticalVectorsWithoutNaNOrInfinity() {
        double[][] result = TwoDProjection.project(List.of(
                new float[]{2.0f, 2.0f},
                new float[]{2.0f, 2.0f},
                new float[]{2.0f, 2.0f}));

        for (double[] point : result) {
            assertEquals(0.0, point[0]);
            assertEquals(0.0, point[1]);
            assertFalse(Double.isNaN(point[0]));
            assertFalse(Double.isNaN(point[1]));
            assertFalse(Double.isInfinite(point[0]));
            assertFalse(Double.isInfinite(point[1]));
        }
    }

    @Test
    void degradesToZeroInsteadOfNaNOrInfinityWhenAnEmbeddingContainsNonFiniteValues() {
        double[][] result = TwoDProjection.project(List.of(
                new float[]{Float.NaN, 1.0f},
                new float[]{2.0f, Float.POSITIVE_INFINITY},
                new float[]{3.0f, 4.0f}));

        for (double[] point : result) {
            assertFalse(Double.isNaN(point[0]));
            assertFalse(Double.isNaN(point[1]));
            assertFalse(Double.isInfinite(point[0]));
            assertFalse(Double.isInfinite(point[1]));
        }
    }

    @Test
    void fitThenProjectAllMatchesTheOneStepBatchProjection() {
        List<float[]> vectors = List.of(
                new float[]{1.0f, 0.0f},
                new float[]{3.0f, 0.0f},
                new float[]{5.0f, 0.0f});

        double[][] batch = TwoDProjection.project(vectors);
        ProjectionModel model = TwoDProjection.fit(vectors);
        double[][] viaModel = TwoDProjection.projectAll(model, vectors);

        for (int i = 0; i < vectors.size(); i++) {
            assertEquals(batch[i][0], viaModel[i][0], 1.0e-9);
            assertEquals(batch[i][1], viaModel[i][1], 1.0e-9);
        }
    }

    @Test
    void projectingASingleOutOfBandVectorReusesTheFittedModelWithoutReshufflingIt() {
        List<float[]> corpusVectors = List.of(
                new float[]{1.0f, 0.0f},
                new float[]{5.0f, 0.0f});
        ProjectionModel model = TwoDProjection.fit(corpusVectors);

        // A query vector that sits exactly between the two fitted points should
        // land at (roughly) the midpoint of their projected x-coordinates.
        double[] midpointQuery = TwoDProjection.project(model, new float[]{3.0f, 0.0f});
        double[][] corpusProjected = TwoDProjection.projectAll(model, corpusVectors);
        double expectedMidX = (corpusProjected[0][0] + corpusProjected[1][0]) / 2.0;

        assertEquals(expectedMidX, midpointQuery[0], 1.0e-9);
    }

    @Test
    void projectingWithAnUnfittedOrEmptyModelDegradesToTheOriginInsteadOfThrowing() {
        double[] result = TwoDProjection.project(new ProjectionModel(new double[0], new double[0], new double[0]),
                new float[]{1.0f, 2.0f});

        assertEquals(0.0, result[0]);
        assertEquals(0.0, result[1]);
    }
}
