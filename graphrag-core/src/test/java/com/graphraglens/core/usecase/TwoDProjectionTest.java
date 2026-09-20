package com.graphraglens.core.usecase;

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
}
