package dev.rabauer.graphrag.core.community;

/**
 * One undirected, weighted edge between two node ids. The direction of
 * {@code source} and {@code target} carries no meaning: {@code a -> b} and
 * {@code b -> a} describe the same edge, and parallel edges sum their weights.
 *
 * @param source one endpoint id; {@code null} is normalised to {@code ""}
 * @param target the other endpoint id; {@code null} is normalised to {@code ""}
 * @param weight the edge weight, kept as given — detectors ignore edges whose
 *               weight is not a positive finite number
 */
public record WeightedEdge(String source, String target, double weight) {

    /** Normalises {@code null} ids to the empty string. */
    public WeightedEdge {
        source = source == null ? "" : source;
        target = target == null ? "" : target;
    }
}
