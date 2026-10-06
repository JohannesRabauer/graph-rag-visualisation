/**
 * Community detection: framework-free, deterministic algorithms that group the
 * nodes of an undirected, weighted graph into communities. {@link
 * dev.rabauer.graphrag.core.community.ModularityCommunityDetector} implements
 * {@link dev.rabauer.graphrag.core.community.CommunityDetector} with seedable
 * Louvain-style modularity optimisation plus a Leiden-style connectivity
 * refinement, and can also report the full hierarchy of levels it builds.
 */
package dev.rabauer.graphrag.core.community;
