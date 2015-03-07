package net.stemmaweb.rest;

import java.util.Iterator;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import net.stemmaweb.model.UserModel;

import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.cypher.javacompat.ExecutionResult;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;

/**
 * 
 * @author jakob, severin
 *
 */
@Path("/user")
public class User {
	
	/**
	 * 
	 * @return User!
	 */
    @GET 
    @Produces("text/plain")
    public String getIt() {
        return "User!";
    }
    
    /**
     * This method can be used to determine whether a user with given Id exists in the DB
     * @param userId
     * @return
     */
    public static boolean checkUserExists(String userId)
    {
    	boolean userExists = false;
    	GraphDatabaseFactory dbFactory = new GraphDatabaseFactory();
    	GraphDatabaseService db= dbFactory.newEmbeddedDatabase("database");
    	
    	ExecutionEngine engine = new ExecutionEngine(db);
    	try(Transaction tx = db.beginTx())
    	{
    		ExecutionResult result = engine.execute("match (userId:USER {id:'"+userId+"'}) return userId");
    		Iterator<Node> nodes = result.columnAs("userId");
    		if(nodes.hasNext())
    			userExists = true;
    		else
    			userExists = false;
    		tx.success();
    	}
    	finally {
    		db.shutdown();
    	}
		return userExists;
    }
    
    /**
     * 
     * @param userModel in JSON Format 
     * @return OK on success or an ERROR as JSON
     */
    @POST
    @Path("create")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(UserModel userModel){
    	
    	if(checkUserExists(userModel.getId()))
    	{
    		return Response.status(Response.Status.CONFLICT).entity("Error: A user with this id already exists").build();
    	}
    	
		GraphDatabaseFactory dbFactory = new GraphDatabaseFactory();
    	GraphDatabaseService db= dbFactory.newEmbeddedDatabase("database");

    	ExecutionEngine engine = new ExecutionEngine(db);
    	try(Transaction tx = db.beginTx())
    	{
    		ExecutionResult rootNodeSearch = engine.execute("match (n:ROOT) return n");
    		Node rootNode = (Node) rootNodeSearch.columnAs("n").next();
    		
    		Node node = db.createNode(Nodes.USER);
    		node.setProperty("id", userModel.getId());
    		node.setProperty("isAdmin", userModel.getIsAdmin());
    			
    		node.createRelationshipTo(rootNode, Relations.NORMAL);

    		tx.success();
    	} finally {
        	db.shutdown();
    	}
    	
    	return Response.status(Response.Status.CREATED).build();
    }
    
    /**
     * 
     * @param userId
     * @return UserModel as JSON
     */
    @GET
	@Path("{userId}")
    @Produces(MediaType.APPLICATION_JSON)
	public Response getUserById(@PathParam("userId") String userId) {
    	UserModel userModel = new UserModel();
    	GraphDatabaseFactory dbFactory = new GraphDatabaseFactory();
    	GraphDatabaseService db= dbFactory.newEmbeddedDatabase("database");

    	ExecutionEngine engine = new ExecutionEngine(db);
    	try(Transaction tx = db.beginTx())
    	{
    		ExecutionResult result = engine.execute("match (userId:USER {id:'"+userId+"'}) return userId");
    		Iterator<Node> nodes = result.columnAs("userId");
    		
    		if(nodes.hasNext()){
    			Node node = nodes.next();
        		userModel.setId((String) node.getProperty("id"));
        		userModel.setIsAdmin((String) node.getProperty("isAdmin"));
    		} else {
    			return Response.status(Response.Status.NOT_FOUND).build();
    		}

    		tx.success();
    	} finally {
        	db.shutdown();
    	}  	
    	return Response.status(Response.Status.OK).entity(userModel).build();
	}
    
    @GET
    @Path("traditions/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTraditionsByUserId(@PathParam("userId") String userId)
    {
    	String json_string = "";
    	if(!checkUserExists(userId))
    	{
    		return Response.status(Response.Status.NOT_FOUND).entity("Error: A user with this id does not exist!").build();
    	}
    	
    	GraphDatabaseFactory dbFactory = new GraphDatabaseFactory();
    	GraphDatabaseService db= dbFactory.newEmbeddedDatabase("database");

    	ExecutionEngine engine = new ExecutionEngine(db);
    	ExecutionResult result = null;
    	try(Transaction tx = db.beginTx())
    	{
    		result = engine.execute("match (n)-[:NORMAL]->(userId:USER {id:'"+userId+"'}) return n");
    		Iterator<Node> traditions = result.columnAs("n");
   			json_string = "{\"traditions\":[";
   			while(traditions.hasNext())
   			{
   				json_string += "{\"name\":\"" + traditions.next().getProperty("name") + "\"}";
  				if(traditions.hasNext())
   					json_string += ",";
   			}
    		json_string += "]}";
    		
    		tx.success();
   		
    	} finally {
    		db.shutdown();
    	}
    	
    	return Response.status(Response.Status.OK).entity(json_string).build();
    }
    
    
}