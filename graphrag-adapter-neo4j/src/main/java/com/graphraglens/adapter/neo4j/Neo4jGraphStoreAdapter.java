package com.graphraglens.adapter.neo4j;

import com.graphraglens.core.domain.ExtractedEntity;
import com.graphraglens.core.domain.ExtractedRelationship;
import com.graphraglens.core.port.GraphStorePort;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

/**
 * Neo4j driver + Cypher implementation of {@link GraphStorePort}.
 */
public class Neo4jGraphStoreAdapter implements GraphStorePort {

    private final Driver driver;

    public Neo4jGraphStoreAdapter(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void mergeEntity(ExtractedEntity entity) {
        try (Session session = driver.session()) {
            session.executeWriteWithoutResult(tx -> tx.run(
                    """
                    MERGE (e:Entity {normalized_key: $normalizedKey})
                    ON CREATE SET e.name = $name, e.type = $type
                    ON MATCH SET e.name = $name, e.type = $type
                    """,
                    Values.parameters(
                            "normalizedKey", entity.normalizedKey(),
                            "name", entity.name(),
                            "type", entity.type())));
        }
    }

    @Override
    public void mergeRelationship(ExtractedRelationship relationship) {
        try (Session session = driver.session()) {
            session.executeWriteWithoutResult(tx -> tx.run(
                    """
                    MERGE (source:Entity {normalized_key: $sourceKey})
                    ON CREATE SET source.name = $sourceName, source.type = $sourceType
                    ON MATCH SET source.name = $sourceName, source.type = $sourceType
                    MERGE (target:Entity {normalized_key: $targetKey})
                    ON CREATE SET target.name = $targetName, target.type = $targetType
                    ON MATCH SET target.name = $targetName, target.type = $targetType
                    MERGE (source)-[r:RELATED_TO {type: $relationshipType}]->(target)
                    """,
                    Values.parameters(
                            "sourceKey", relationship.source().normalizedKey(),
                            "sourceName", relationship.source().name(),
                            "sourceType", relationship.source().type(),
                            "targetKey", relationship.target().normalizedKey(),
                            "targetName", relationship.target().name(),
                            "targetType", relationship.target().type(),
                            "relationshipType", relationship.type())));
        }
    }
}
