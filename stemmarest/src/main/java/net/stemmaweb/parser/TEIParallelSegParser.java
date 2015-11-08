package net.stemmaweb.parser;

import net.stemmaweb.rest.ERelations;
import net.stemmaweb.rest.Nodes;
import net.stemmaweb.rest.Tradition;
import net.stemmaweb.services.GraphDatabaseServiceProvider;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;

import javax.ws.rs.core.Response;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.*;

/**
 * Parse a TEI parallel-segmentation file into a tradition graph.
 */
public class TEIParallelSegParser {
    private GraphDatabaseServiceProvider dbServiceProvider = new GraphDatabaseServiceProvider();
    private GraphDatabaseService db = dbServiceProvider.getDatabase();

    public Response parseTEIParallelSeg(String filename, String userId, String tradName)
            throws FileNotFoundException {
        File file = new File(filename);
        InputStream in = new FileInputStream(file);
        return parseTEIParallelSeg(in, userId, tradName);
    }

    public Response parseTEIParallelSeg(InputStream xmldata, String userId, String tradName) {
        XMLInputFactory factory;
        XMLStreamReader reader;
        factory = XMLInputFactory.newInstance();
        try {
            reader = factory.createXMLStreamReader(xmldata);
        } catch (XMLStreamException e) {
            e.printStackTrace();
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Error: Parsing of tradition file failed!")
                    .build();
        }

        Node graphRoot;
        Node traditionNode;             // this will be the entry point of the graph
        String tradId = UUID.randomUUID().toString();

        // Main XML parser loop
        try (Transaction tx = db.beginTx()) {

            // Set up the tradition and its user
            graphRoot = db.findNode(Nodes.ROOT, "name", "Root node");
            traditionNode = db.createNode(Nodes.TRADITION); // create the root node of tradition
            traditionNode.setProperty("id", tradId);
            traditionNode.setProperty("name", tradName);
            // TODO direction?

            Node userNode = db.findNode(Nodes.USER, "id", userId);
            if (userNode == null) {
                userNode = db.createNode(Nodes.USER);
                userNode.setProperty("id", userId);
                graphRoot.createRelationshipTo(userNode, ERelations.SYSTEMUSER);
            }
            userNode.createRelationshipTo(traditionNode, ERelations.OWNS_TRADITION);

            // Set up the start node
            Node startNode = db.createNode(Nodes.READING);
            startNode.setProperty("is_start", true);
            startNode.setProperty("text", "#START#");
            traditionNode.createRelationshipTo(startNode, ERelations.COLLATION);

            // State variables
            Boolean inHeader = false;
            Boolean inText = false;
            Boolean inWord = false;
            Boolean skip = false;
            HashMap<String, Node> priorNode = new HashMap<>();
            HashMap<String, Boolean> activeWitnesses = new HashMap<>();
            ArrayList<String> readingWitnesses = new ArrayList<>();
            String witClass = "witnesses";
            // Fill in the contents of the tradition
            // TODO consider supporting illegible readings, gaps, etc.
            parseloop: while (true) {
                int event = reader.next();
                String local_name = reader.getLocalName();

                switch(event) {
                    case XMLStreamConstants.END_DOCUMENT:
                        reader.close();
                        break parseloop;

                    case XMLStreamConstants.END_ELEMENT:
                        skip = false;
                        switch(local_name) {
                            case "teiHeader":
                                inHeader = false;
                                break;
                            case "app":
                                // Leaving an app, reading witnesses are all active witnesses.
                                // Though this doesn't handle nested apps.
                                // TODO We should implement nested apps as a sort of stack.
                                readingWitnesses = new ArrayList<>();
                                activeWitnesses.keySet().stream()
                                        .filter(activeWitnesses::get)
                                        .forEach(readingWitnesses::add);
                                break;

                            case "rdg":
                            case "lem":
                                readingWitnesses = new ArrayList<>();
                                witClass = "witnesses";
                                break;

                            case "text":
                                // End of the text; add the end node.
                                Node endNode = db.createNode(Nodes.READING);
                                endNode.setProperty("text", "#END#");
                                endNode.setProperty("is_end", true);
                                traditionNode.createRelationshipTo(endNode, ERelations.HAS_END);
                                HashSet<Node> lastNodes = new HashSet<>();
                                activeWitnesses.keySet().forEach(x -> lastNodes.add(priorNode.get(x)));

                                HashSet<Relationship> endLinks = new HashSet<>();
                                lastNodes.forEach(x -> {
                                    if (!x.equals(startNode)) {
                                        Relationship seq = x.createRelationshipTo(endNode, ERelations.SEQUENCE);
                                        endLinks.add(seq);
                                    }
                                });

                                final String wc = witClass;
                                activeWitnesses.keySet().forEach(w -> endLinks.forEach(l -> {
                                    if (l.getStartNode().equals(priorNode.get(w)))
                                        addWitness(l, w, wc);
                                }));
                                break;

                            case "w": // This delineates a new word, no matter the whitespace.
                                inWord = false;
                                break;

                        }

                    case XMLStreamConstants.START_ELEMENT:
                        switch(local_name) {

                            // Deal with information from the TEI header
                            case "teiHeader":
                                inHeader = true;
                                break;

                            case "witness":
                                if(inHeader) {
                                    String sigil = reader.getAttributeValue("", "xml:id");
                                    Node witnessNode = db.createNode(Nodes.WITNESS);
                                    witnessNode.setProperty("sigil", sigil);
                                    witnessNode.setProperty("hypothetical", false);
                                    witnessNode.setProperty("quotesigil", isDotId(sigil));
                                    traditionNode.createRelationshipTo(witnessNode, ERelations.HAS_WITNESS);
                                    activeWitnesses.put(sigil, false);
                                }
                                break;

                            case "title":
                                if(inHeader && tradName.equals("")) {
                                    traditionNode.setProperty("name", reader.getElementText());
                                }
                                break;

                            // Now parse the text body
                            case "text":
                                if(!inHeader) {
                                    inText = true;
                                    activeWitnesses.keySet().forEach(x -> priorNode.put(x, startNode));
                                }
                                break;

                            case "rdg":
                            case "lem":
                                readingWitnesses = parseWitnesses(reader.getAttributeValue("", "wit"));
                                witClass = reader.getAttributeValue("", "type");
                                break;

                            case "witStart":
                                readingWitnesses.forEach(x -> activeWitnesses.put(x, true));
                                break;
                            case "witEnd":
                                readingWitnesses.forEach(x -> activeWitnesses.put(x, false));
                                break;

                            case "w": // This delineates a new word, no matter the whitespace.
                                inWord = false;
                                break;

                            case "witDetail":
                            case "note":
                                skip = true;

                            // default: mark some text annotation node

                        }
                        break;

                    case XMLStreamConstants.CHARACTERS:
                        if(inText && !skip) {
                            // Split the character stream into whitespace-separate words
                            String[] words = reader.getText().split("\\s");
                            // See if this stream of characters begins in the middle of a word
                            // final Boolean inWordNow = inWord && !words[0].equals("");
                            // Make the new chain of reading nodes
                            HashSet<Node> allPriors = new HashSet<>();
                            readingWitnesses.forEach(x -> allPriors.add(priorNode.get(x)));
                            int s = 0;
                            Node pn = null;
                            if (inWord) {
                                if (allPriors.size() == 1) {
                                    pn = allPriors.iterator().next();
                                    // Add the first word to the prior reading
                                    pn.setProperty("text", pn.getProperty("text").toString() + words[0]);
                                } else {
                                    // More complicated. We make a join-prior reading to join onto the
                                    // respective prior nodes.
                                    if (!words[0].equals("")) {
                                        Node wordNode = db.createNode(Nodes.READING);
                                        wordNode.setProperty("text", words[0]);
                                        wordNode.setProperty("join_prior", true);
                                        // Connect them up on this witness
                                        HashSet<Relationship> priorLinks = new HashSet<>();
                                        allPriors.forEach(n -> {
                                            Relationship seq = n.createRelationshipTo(wordNode, ERelations.SEQUENCE);
                                            priorLinks.add(seq);
                                        });
                                        final String wc = witClass;
                                        readingWitnesses.forEach(w -> priorLinks.forEach(l -> {
                                            if(l.getStartNode().equals(priorNode.get(w)))
                                                addWitness(l, w, wc);
                                        }));
                                        pn = wordNode;
                                   } // else we aren't really in the middle of a word, and the prior node
                                     // will depend on the witness. Then pn will be undefined.
                                }
                                s = 1;
                            }

                            // Make the chain of readings we need
                            ArrayList<Node> chain = new ArrayList<>();
                            for (int i = s; i < words.length; i++) {
                                String word = words[i];
                                Node wordNode = db.createNode(Nodes.READING);
                                wordNode.setProperty("text", word);
                                if (!chain.isEmpty()) {
                                    Node lastNode = chain.get(chain.size()-1);
                                    Relationship seq = lastNode.createRelationshipTo(wordNode, ERelations.SEQUENCE);
                                    seq.setProperty(witClass, readingWitnesses.toArray());
                                }
                                chain.add(wordNode);
                            }

                            // Attach the chain to the relevant prior node
                            if (pn != null) {
                                Relationship seq = pn.createRelationshipTo(chain.get(0), ERelations.SEQUENCE);
                                seq.setProperty(witClass, readingWitnesses.toArray());
                            } else { // We have to iterate through multiple prior nodes.
                                Node firstNode = chain.get(0);
                                HashSet<Relationship> priorLinks = new HashSet<>();
                                allPriors.forEach(n -> {
                                    Relationship seq = n.createRelationshipTo(firstNode, ERelations.SEQUENCE);
                                    priorLinks.add(seq);
                                });
                                final String wc = witClass;
                                readingWitnesses.forEach(w -> priorLinks.forEach(l -> {
                                    if (l.getStartNode().equals(priorNode.get(w)))
                                        addWitness(l, w, wc);
                                }));
                            }

                            // Set the new prior node for these witnesses
                            readingWitnesses.forEach(w -> priorNode.put(w, chain.get(chain.size() - 1)));

                           // Note whether we ended in the middle of a word
                            inWord = !reader.getText().endsWith(" ");
                        }
                        break;
                }
            }

            // Now calculate the whole tradition.
            Tradition newTrad = new Tradition(tradId);
            Boolean nodesRanked = newTrad.recalculateRank(startNode.getId());
            if(nodesRanked)
                tx.success();
            else
                return Response.serverError().entity("Unable to rank final graph").build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().build();
        }

        return Response.status(Response.Status.CREATED).entity("{\"tradId\":" + tradId + "}").build();
    }

    private ArrayList<String> parseWitnesses (String witString) {
        ArrayList<String> wits = new ArrayList<>();
        for (String w : witString.split("\\s")) {
            wits.add(w.substring(1));
        }
        return wits;
    }

    private void addWitness (Relationship r, String sigil, String witClass) {
        try (Transaction tx = db.beginTx()) {
            if(r.hasProperty(witClass)) {
                String[] witList = (String[]) r.getProperty(witClass);
                ArrayList<String> currentWits = new ArrayList<>(Arrays.asList(witList));
                currentWits.add(sigil);
                r.setProperty(witClass, currentWits.toArray(new String[currentWits.size()]));
            } else {
                String[] witList = {sigil};
                r.setProperty(witClass, witList);
            }
            tx.success();
        }
    }

    private Boolean isDotId (String nodeid) {
        return nodeid.matches("^[A-Za-z][A-Za-z0-9_.]*$")
                || nodeid.matches("^-?(\\.\\d+|\\d+\\.\\d+)$");
    }

}