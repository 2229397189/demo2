package com.agi.assistant.service.memory;

import com.agi.assistant.model.entity.GraphEntity;
import com.agi.assistant.model.entity.GraphRelation;
import com.agi.assistant.model.enums.NodeType;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.types.Node;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j-based graph memory service.
 * <p>
 * Manages a knowledge graph with node types: User, Memory, Topic, Entity.
 * Relationships: HAS_MEMORY, FOLLOWS, SIMILAR_TO, ABOUT, MENTIONS.
 */
@Slf4j
@Lazy
@Service
public class GraphMemory {

    private final Driver neo4jDriver;

    public GraphMemory(@Nullable Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    private boolean isNeo4jAvailable() {
        if (neo4jDriver == null) {
            log.warn("Neo4j not available, skipping graph operation");
            return false;
        }
        return true;
    }

    // ----------------------------------------------------------------
    //  Memory Node Management
    // ----------------------------------------------------------------

    /**
     * Add a memory node to the graph and link it to a user.
     *
     * @param userId    the user identifier
     * @param memoryId  a unique identifier for this memory
     * @param content   the memory text content
     * @param importance the importance score (0.0 - 1.0)
     * @return the created GraphEntity representing the memory node
     */
    public GraphEntity addMemoryNode(Long userId, String memoryId, String content, double importance) {
        if (!isNeo4jAvailable() || userId == null || memoryId == null || content == null) {
            return null;
        }

        try (Session session = neo4jDriver.session()) {
            String cypher =
                    "MERGE (m:Memory {memoryId: $memoryId})\n" +
                    "SET m.content = $content, " +
                    "    m.importance = $importance, " +
                    "    m.createdAt = $createdAt, " +
                    "    m.accessCount = 0\n" +
                    "WITH m\n" +
                    "MERGE (u:User {userId: $userId})\n" +
                    "MERGE (u)-[:HAS_MEMORY]->(m)\n" +
                    "RETURN m";

            Map<String, Object> params = Map.of(
                    "memoryId", memoryId,
                    "content", content,
                    "importance", importance,
                    "userId", userId.toString(),
                    "createdAt", LocalDateTime.now().toString()
            );

            Result result = session.run(cypher, params);
            if (result.hasNext()) {
                Record record = result.next();
                Node node = record.get("m").asNode();
                log.debug("Added memory node [{}] for user [{}]", memoryId, userId);
                return nodeToEntity(node, "Memory");
            }

        } catch (Exception e) {
            log.error("Failed to add memory node [{}]: {}", memoryId, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Link a memory node to a topic.
     *
     * @param memoryId the memory identifier
     * @param topic    the topic name
     */
    public void linkMemoryToTopic(String memoryId, String topic) {
        if (!isNeo4jAvailable() || memoryId == null || topic == null || topic.isBlank()) {
            return;
        }

        try (Session session = neo4jDriver.session()) {
            String cypher =
                    "MERGE (t:Topic {name: $topic})\n" +
                    "WITH t\n" +
                    "MATCH (m:Memory {memoryId: $memoryId})\n" +
                    "MERGE (m)-[:ABOUT]->(t)\n" +
                    "RETURN t.name AS topic";

            Map<String, Object> params = Map.of(
                    "memoryId", memoryId,
                    "topic", topic
            );

            session.run(cypher, params);
            log.debug("Linked memory [{}] to topic [{}]", memoryId, topic);

        } catch (Exception e) {
            log.error("Failed to link memory [{}] to topic [{}]: {}", memoryId, topic, e.getMessage(), e);
        }
    }

    /**
     * Link a memory node to an entity.
     *
     * @param memoryId   the memory identifier
     * @param entityName the entity name
     * @param entityType the entity type (e.g. "Person", "Concept")
     */
    public void linkMemoryToEntity(String memoryId, String entityName, String entityType) {
        if (!isNeo4jAvailable() || memoryId == null || entityName == null || entityName.isBlank()) {
            return;
        }

        try (Session session = neo4jDriver.session()) {
            String cypher =
                    "MERGE (e:Entity {name: $entityName})\n" +
                    "SET e.type = $entityType\n" +
                    "WITH e\n" +
                    "MATCH (m:Memory {memoryId: $memoryId})\n" +
                    "MERGE (m)-[:MENTIONS]->(e)\n" +
                    "RETURN e.name AS name";

            Map<String, Object> params = Map.of(
                    "memoryId", memoryId,
                    "entityName", entityName,
                    "entityType", entityType != null ? entityType : "Unknown"
            );

            session.run(cypher, params);
            log.debug("Linked memory [{}] to entity [{}]", memoryId, entityName);

        } catch (Exception e) {
            log.error("Failed to link memory [{}] to entity [{}]: {}", memoryId, entityName, e.getMessage(), e);
        }
    }

    /**
     * Get memories related to a given memory via graph traversal.
     *
     * @param memoryId the starting memory identifier
     * @param maxDepth the maximum traversal depth
     * @return list of related memory content and their relation info
     */
    public List<Map<String, Object>> getRelatedMemories(String memoryId, int maxDepth) {
        if (!isNeo4jAvailable() || memoryId == null) {
            return Collections.emptyList();
        }

        int depth = Math.max(1, Math.min(maxDepth, 3));

        try (Session session = neo4jDriver.session()) {
            String cypher =
                    "MATCH path = (start:Memory {memoryId: $memoryId})-[*1.." + depth + "]-(related:Memory)\n" +
                    "WHERE start <> related\n" +
                    "RETURN DISTINCT related.memoryId AS memoryId, " +
                    "       related.content AS content, " +
                    "       related.importance AS importance, " +
                    "       length(path) AS distance\n" +
                    "ORDER BY related.importance DESC, distance ASC\n" +
                    "LIMIT 20";

            Map<String, Object> params = Map.of("memoryId", memoryId);
            Result result = session.run(cypher, params);

            List<Map<String, Object>> related = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                Map<String, Object> item = new HashMap<>();
                item.put("memoryId", record.get("memoryId").asString());
                item.put("content", record.get("content").asString());
                item.put("importance", record.get("importance").asDouble());
                item.put("distance", record.get("distance").asInt());
                related.add(item);
            }

            log.debug("Found {} related memories for [{}]", related.size(), memoryId);
            return related;

        } catch (Exception e) {
            log.error("Failed to get related memories for [{}]: {}", memoryId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Get the memory chain for a user following FOLLOWS relationships.
     * Returns memories in temporal order.
     *
     * @param userId the user identifier
     * @param limit  maximum number of memories to return
     * @return ordered list of memory nodes in the chain
     */
    public List<Map<String, Object>> getMemoryChain(Long userId, int limit) {
        if (!isNeo4jAvailable() || userId == null) {
            return Collections.emptyList();
        }

        try (Session session = neo4jDriver.session()) {
            // Get root memory (one with no incoming FOLLOWS from another memory)
            String cypher =
                    "MATCH (u:User {userId: $userId})-[:HAS_MEMORY]->(m:Memory)\n" +
                    "WHERE NOT ()-[:FOLLOWS]->(m)\n" +
                    "OPTIONAL MATCH chain = (m)-[:FOLLOWS*0.." + (limit - 1) + "]->(successor:Memory)\n" +
                    "UNWIND (CASE WHEN chain IS NULL THEN [m] ELSE nodes(chain) END) AS mem\n" +
                    "RETURN DISTINCT mem.memoryId AS memoryId, " +
                    "       mem.content AS content, " +
                    "       mem.importance AS importance, " +
                    "       mem.createdAt AS createdAt\n" +
                    "ORDER BY mem.createdAt ASC\n" +
                    "LIMIT $limit";

            Map<String, Object> params = Map.of(
                    "userId", userId.toString(),
                    "limit", limit
            );

            Result result = session.run(cypher, params);
            List<Map<String, Object>> chain = new ArrayList<>();

            while (result.hasNext()) {
                Record record = result.next();
                Map<String, Object> item = new HashMap<>();
                item.put("memoryId", record.get("memoryId").asString());
                item.put("content", record.get("content").asString());
                item.put("importance", record.get("importance").asDouble());
                item.put("createdAt", record.get("createdAt").asString());
                chain.add(item);
            }

            log.debug("Retrieved memory chain for user [{}]: {} items", userId, chain.size());
            return chain;

        } catch (Exception e) {
            log.error("Failed to get memory chain for user [{}]: {}", userId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Create a FOLLOWS relationship between two memories (temporal ordering).
     *
     * @param fromMemoryId the earlier memory
     * @param toMemoryId   the later memory
     */
    public void linkMemorySequence(String fromMemoryId, String toMemoryId) {
        if (!isNeo4jAvailable() || fromMemoryId == null || toMemoryId == null) {
            return;
        }

        try (Session session = neo4jDriver.session()) {
            String cypher =
                    "MATCH (a:Memory {memoryId: $fromId})\n" +
                    "MATCH (b:Memory {memoryId: $toId})\n" +
                    "MERGE (a)-[:FOLLOWS]->(b)\n" +
                    "RETURN a.memoryId, b.memoryId";

            Map<String, Object> params = Map.of(
                    "fromId", fromMemoryId,
                    "toId", toMemoryId
            );

            session.run(cypher, params);
            log.debug("Linked memory sequence: {} -> {}", fromMemoryId, toMemoryId);

        } catch (Exception e) {
            log.error("Failed to link memory sequence {} -> {}: {}",
                    fromMemoryId, toMemoryId, e.getMessage(), e);
        }
    }

    /**
     * Create a SIMILAR_TO relationship between two memories.
     *
     * @param memoryIdA first memory identifier
     * @param memoryIdB second memory identifier
     * @param similarityScore the similarity score
     */
    public void linkSimilarMemories(String memoryIdA, String memoryIdB, double similarityScore) {
        if (!isNeo4jAvailable() || memoryIdA == null || memoryIdB == null) {
            return;
        }

        try (Session session = neo4jDriver.session()) {
            String cypher =
                    "MATCH (a:Memory {memoryId: $idA})\n" +
                    "MATCH (b:Memory {memoryId: $idB})\n" +
                    "MERGE (a)-[r:SIMILAR_TO]->(b)\n" +
                    "SET r.score = $score\n" +
                    "RETURN a.memoryId, b.memoryId";

            Map<String, Object> params = Map.of(
                    "idA", memoryIdA,
                    "idB", memoryIdB,
                    "score", similarityScore
            );

            session.run(cypher, params);
            log.debug("Linked similar memories: {} <-> {} (score={})",
                    memoryIdA, memoryIdB, similarityScore);

        } catch (Exception e) {
            log.error("Failed to link similar memories {} <-> {}: {}",
                    memoryIdA, memoryIdB, e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------
    //  Internal
    // ----------------------------------------------------------------

    private GraphEntity nodeToEntity(Node node, String defaultType) {
        String entityId = node.containsKey("memoryId")
                ? node.get("memoryId").asString()
                : node.containsKey("name") ? node.get("name").asString() : String.valueOf(node.id());

        String name = node.containsKey("name") ? node.get("name").asString() : entityId;
        String type = node.containsKey("type") ? node.get("type").asString() : defaultType;

        Map<String, Object> properties = new HashMap<>();
        node.keys().forEach(key -> {
            try {
                properties.put(key, node.get(key).asObject());
            } catch (Exception ignored) {
                // skip non-serializable properties
            }
        });

        return GraphEntity.builder()
                .entityId(entityId)
                .name(name)
                .type(type)
                .properties(properties)
                .score(1.0)
                .build();
    }
}
