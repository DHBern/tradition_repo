package net.stemmaweb.services;

import net.stemmaweb.model.RelationTypeModel;
import net.stemmaweb.rest.ERelations;
import net.stemmaweb.rest.Nodes;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

public class VariantGraphService {

    /**
     * Check whether a given section actually belongs to the given tradition.
     *
     * @param tradId - The alleged parent tradition
     * @param aSectionId - The section to check
     * @param db - the GraphDatabaseService where the tradition is stored
     * @return - true or false
     */
    public static Boolean sectionInTradition(String tradId, String aSectionId, GraphDatabaseService db) {
        Node traditionNode = getTraditionNode(tradId, db);
        if (traditionNode == null)
            return false;

        boolean found = false;
        try (Transaction tx = db.beginTx()) {
            for (Node s : DatabaseService.getRelated(traditionNode, ERelations.PART)) {
                if (s.getId() == Long.valueOf(aSectionId)) {
                    found = true;
                }
            }
            tx.success();
        }
        return found;
    }

    /**
     * Get the start node of a section, or the first section in a tradition
     *
     * @param nodeId the ID of the tradition or section whose start node should be returned
     * @param db  the GraphDatabaseService where the tradition is stored
     * @return  the start node, or null if there is none.
     *      NOTE if there are multiple unordered sections, an arbitrary start node may be returned!
     */
    public static Node getStartNode(String nodeId, GraphDatabaseService db) {
        return getBoundaryNode(nodeId, db, ERelations.COLLATION);
    }

    /**
     * Get the end node of a section, or the last section in a tradition
     *
     * @param nodeId the ID of the tradition or section whose end node should be returned
     * @param db  the GraphDatabaseService where the tradition is stored
     * @return  the end node, or null if there is none
     *      NOTE if there are multiple unordered sections, an arbitrary end node may be returned!
     */
    public static Node getEndNode(String nodeId, GraphDatabaseService db) {
        return getBoundaryNode(nodeId, db, ERelations.HAS_END);
    }

    private static Node getBoundaryNode(String nodeId, GraphDatabaseService db, ERelations direction) {
        Node boundNode = null;
        // If we have been asked for a tradition node, use either the first or the last of
        // its section nodes instead.
        Node currentNode = getTraditionNode(nodeId, db);
        if (currentNode != null) {
            ArrayList<Node> sections = getSectionNodes(nodeId, db);
            if (sections != null && sections.size() > 0) {
                Node relevantSection = direction.equals(ERelations.HAS_END)
                        ? sections.get(sections.size() - 1)
                        : sections.get(0);
                return getBoundaryNode(String.valueOf(relevantSection.getId()), db, direction);
            } else return null;
        }
        // Were we asked for a nonexistent tradition node (i.e. a non-Long that corresponds to no tradition)?
        long nodeIndex;
        try {
            nodeIndex = Long.valueOf(nodeId);
        } catch (NumberFormatException e) {
            return null;
        }
        // If we are here, we were asked for a section node.
        try (Transaction tx = db.beginTx()) {
            currentNode = db.getNodeById(nodeIndex);
            if (currentNode != null)
                boundNode = currentNode.getSingleRelationship(direction, Direction.OUTGOING).getEndNode();
            tx.success();
        }
        return boundNode;
    }

    /**
     * Return the list of a tradition's sections, ordered by NEXT relationship
     *
     * @param tradId    the tradition whose sections to return
     * @param db        the GraphDatabaseService where the tradition is stored
     * @return          a list of sections, or null if the tradition doesn't exist
     */
    public static ArrayList<Node> getSectionNodes(String tradId, GraphDatabaseService db) {
        Node tradition = getTraditionNode(tradId, db);
        if (tradition == null)
            return null;
        ArrayList<Node> sectionNodes = new ArrayList<>();
        ArrayList<Node> sections = DatabaseService.getRelated(tradition, ERelations.PART);
        int size = sections.size();
        try (Transaction tx = db.beginTx()) {
            for(Node n: sections) {
                if (!n.getRelationships(Direction.INCOMING, ERelations.NEXT)
                        .iterator()
                        .hasNext()) {
                    db.traversalDescription()
                            .depthFirst()
                            .relationships(ERelations.NEXT, Direction.OUTGOING)
                            .evaluator(Evaluators.toDepth(size))
                            .uniqueness(Uniqueness.NODE_GLOBAL)
                            .traverse(n)
                            .nodes()
                            .forEach(sectionNodes::add);
                    break;
                }
            }
            tx.success();
        }
        return sectionNodes;
    }

    /**
     * Get the node of the specified tradition
     *
     * @param tradId  the string ID of the tradition we're hunting
     * @param db      the GraphDatabaseService where the tradition is stored
     * @return        the relevant tradition node
     */
    public static Node getTraditionNode(String tradId, GraphDatabaseService db) {
        Node tradition;
        try (Transaction tx = db.beginTx()) {
            tradition = db.findNode(Nodes.TRADITION, "id", tradId);
            tx.success();
        }
        return tradition;
    }

    /**
     * Get the tradition node that the specified section belongs to
     *
     * @param section  the section node whose tradition we're hunting
     * @return         the relevant tradition node
     */
    public static Node getTraditionNode(Node section) {
        Node tradition;
        GraphDatabaseService db = section.getGraphDatabase();
        try (Transaction tx = db.beginTx()) {
            tradition = section.getSingleRelationship(ERelations.PART, Direction.INCOMING).getStartNode();
            tx.success();
        }
        return tradition;
    }

    /*
     * Methods for calcuating and removing shadow graphs - normalization and majority text
     */

    /**
     * Make a graph normalization sequence on the given section according to the given relation type, and
     * return a map of each section nodes to its representative node.
     *
     * @param sectionNode     The section to be normalized
     * @param normalizeType   The (string) name of the type on which we are normalizing
     * @return                A HashMap of nodes to their representatives
     *
     * @throws                Exception, if clusters cannot be got, if the requested relation type doesn't
     *                        exist, or if something goes wrong with the transaction
     */

    public static HashMap<Node,Node> normalizeGraph(Node sectionNode, String normalizeType) throws Exception {
        HashMap<Node,Node> representatives = new HashMap<>();
        GraphDatabaseService db = sectionNode.getGraphDatabase();
        // Make sure the relation type exists
        Node tradition = getTraditionNode(sectionNode);
        Node relType = new RelationTypeModel(normalizeType).lookup(tradition);
        if (relType == null)
            throw new Exception("Relation type " + normalizeType + " does not exist in this tradition");

        try (Transaction tx = db.beginTx()) {
            Node sectionStart = sectionNode.getSingleRelationship(ERelations.COLLATION, Direction.OUTGOING).getEndNode();
            // Get the list of all readings in this section
            Set<Node> sectionNodes = returnTraditionSection(sectionNode).nodes().stream()
                    .filter(x -> x.hasLabel(Label.label("READING"))).collect(Collectors.toSet());

            // Find the normalisation clusters and nominate a representative for each
            String tradId = tradition.getProperty("id").toString();
            String sectionId = String.valueOf(sectionNode.getId());
            for (Set<Node> cluster : RelationService.getCloselyRelatedClusters(
                    tradId, sectionId, db, normalizeType)) {
                Node representative = RelationService.findRepresentative(cluster);
                // Set the representative for all cluster members.
                for (Node n : cluster) {
                    representatives.put(n, representative);
                    if (!sectionNodes.remove(n))
                        throw new Exception("Tried to equivalence a node (" + n.getId() + ") that was not in sectionNodes");
                }
            }

            // All remaining un-clustered readings are represented by themselves
            sectionNodes.forEach(x -> representatives.put(x, x));

            // Now that we have done this, make the shadow sequence
            for (Relationship r : db.traversalDescription().breadthFirst()
                    .relationships(ERelations.SEQUENCE,Direction.OUTGOING)
                    .uniqueness(Uniqueness.RELATIONSHIP_GLOBAL).traverse(sectionStart).relationships()) {
                Node repstart = representatives.getOrDefault(r.getStartNode(), r.getStartNode());
                Node repend = representatives.getOrDefault(r.getEndNode(), r.getEndNode());
                ReadingService.transferWitnesses(repstart, repend, r, ERelations.NSEQUENCE);
            }
            tx.success();
        }

        return representatives;

    }

    /**
     * Clean up after performing normalizeGraph.
     *
     * @param sectionNode  the section to clean up
     * @throws             Exception, if anything was missed
     */

    public static void removeNormalization(Node sectionNode) throws Exception {
        GraphDatabaseService db = sectionNode.getGraphDatabase();
        try (Transaction tx = db.beginTx()) {
            Node sectionStartNode = sectionNode.getSingleRelationship(ERelations.COLLATION, Direction.OUTGOING).getEndNode();
            // Now that it is all written out, flush the shadow sequences if they were created
            db.traversalDescription().breadthFirst()
                    .relationships(ERelations.NSEQUENCE,Direction.OUTGOING)
                    .uniqueness(Uniqueness.RELATIONSHIP_GLOBAL)
                    .traverse(sectionStartNode).relationships()
                    .forEach(Relationship::delete);

            // TEMPORARY: Check that we aren't polluting the graph DB
            if (VariantGraphService.returnTraditionSection(sectionNode).relationships()
                    .stream().anyMatch(x -> x.isType(ERelations.NSEQUENCE)))
                throw new Exception("Data consistency error on normalisation of section " + sectionNode.getId());
            tx.success();
        }
    }

    /*
     * Tradition and section crawlers, respectively
     */

    private static Evaluator traditionCrawler = path -> {
        if (path.length() == 0)
            return Evaluation.INCLUDE_AND_CONTINUE;
        if (path.lastRelationship().getType().name().equals(ERelations.OWNS_TRADITION.toString()))
            return Evaluation.EXCLUDE_AND_PRUNE;
        return Evaluation.INCLUDE_AND_CONTINUE;
    };

    private static Evaluator sectionCrawler = path -> {
        if (path.length() == 0)
            return Evaluation.INCLUDE_AND_CONTINUE;
        String type = path.lastRelationship().getType().name();
        if (type.equals(ERelations.PART.toString()) || type.equals(ERelations.NEXT.toString()))
            return Evaluation.EXCLUDE_AND_PRUNE;
        return Evaluation.INCLUDE_AND_CONTINUE;
    };

    private static Evaluator traditionRelations = path -> {
        if (path.length() == 0)
            return Evaluation.INCLUDE_AND_CONTINUE;
        if (path.lastRelationship().getType().name().equals(ERelations.OWNS_TRADITION.toString()))
            return Evaluation.EXCLUDE_AND_PRUNE;
        if (path.lastRelationship().getType().name().equals(ERelations.RELATED.toString()))
            return Evaluation.INCLUDE_AND_CONTINUE;
        return Evaluation.EXCLUDE_AND_CONTINUE;
    };

    private static Traverser returnTraverser (Node startNode, Evaluator ev, PathExpander ex) {
        Traverser tv;
        GraphDatabaseService db = startNode.getGraphDatabase();
        try (Transaction tx = db.beginTx()) {
            tv = db.traversalDescription()
                    .depthFirst()
                    .expand(ex)
                    .evaluator(ev)
                    .uniqueness(Uniqueness.RELATIONSHIP_GLOBAL)
                    .traverse(startNode);
            tx.success();
        }
        return tv;
    }

    /**
     * Return a traverser that includes all nodes and relationships for everything in a tradition.
     *
     * @param tradId  the string ID of the tradition to crawl
     * @param db      the relevant GraphDatabaseService
     * @return        an org.neo4j.graphdb.traversal.Traverser object for the whole tradition
     */
    public static Traverser returnEntireTradition(String tradId, GraphDatabaseService db) {
        return returnEntireTradition(getTraditionNode(tradId, db));
    }

    /**
     * Return a traverser that includes all nodes and relationships for everything in a tradition.
     *
     * @param traditionNode   the Node object of the tradition to crawl
     * @return                an org.neo4j.graphdb.traversal.Traverser object for the whole tradition
     */
    public static Traverser returnEntireTradition(Node traditionNode) {
        return returnTraverser(traditionNode, traditionCrawler, PathExpanders.forDirection(Direction.OUTGOING));
    }

    /**
     * Return a traverser that includes all nodes and relationships for a particular section.
     *
     * @param sectionId  the string ID of the section to crawl
     * @param db         the relevant GraphDatabaseService
     * @return           an org.neo4j.graphdb.traversal.Traverser object for the section
     */
    public static Traverser returnTraditionSection(String sectionId, GraphDatabaseService db) {
        Traverser tv;
        try (Transaction tx = db.beginTx()) {
            Node sectionNode = db.getNodeById(Long.valueOf(sectionId));
            tv = returnTraditionSection(sectionNode);
            tx.success();
        }
        return tv;
    }

    /**
     * Return a traverser that includes all nodes and relationships for a particular section.
     *
     * @param sectionNode  the Node object of the section to crawl
     * @return             an org.neo4j.graphdb.traversal.Traverser object for the section
     */
    public static Traverser returnTraditionSection(Node sectionNode) {
        return returnTraverser(sectionNode, sectionCrawler, PathExpanders.forDirection(Direction.OUTGOING));
    }

    /**
     * Return a traverser that includes all RELATED relationships in a tradition.
     *
     * @param traditionNode the Node object of the tradition to crawl
     * @return             an org.neo4j.graphdb.traversal.Traverser object containing the relations
     */
    public static Traverser returnTraditionRelations(Node traditionNode) {
        return returnTraverser(traditionNode, traditionRelations, PathExpanders.allTypesAndDirections());
    }
}
