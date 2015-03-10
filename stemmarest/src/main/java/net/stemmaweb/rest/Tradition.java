package net.stemmaweb.rest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import net.stemmaweb.rest.Nodes;
import net.stemmaweb.rest.Relations;
import net.stemmaweb.services.GraphMLToNeo4JParser;
import net.stemmaweb.services.Neo4JToGraphMLParser;

import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.cypher.javacompat.ExecutionResult;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;

import com.sun.jersey.core.header.FormDataContentDisposition;
import com.sun.jersey.multipart.FormDataParam;

import net.stemmaweb.model.*;


/**
 * 
 * @author ramona, sevi
 *
 **/

@Path("/tradition")
public class Tradition {
	public static final String DB_PATH = "database";

	@GET
	@Path("witness")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response getWitness() {
		
		ArrayList<WitnessModel> witlist= new ArrayList<WitnessModel>();

		GraphDatabaseFactory dbFactory = new GraphDatabaseFactory();
		GraphDatabaseService db= dbFactory.newEmbeddedDatabase(DB_PATH);
		
		ExecutionEngine engine = new ExecutionEngine(db);
	
		
		try (Transaction tx = db.beginTx()) 
		{
			
			tx.success();
		}
		catch(Exception e)
	    {
	    	e.printStackTrace();
	    }	
		finally
		{
			db.shutdown();
		}
		//return Response.status(Status.NOT_FOUND).build();
		
		return Response.ok().build();
	}
	
	/**
	 * Returns GraphML file from specified tradition owned by user
	 * @param userId
	 * @param traditionName
	 * @return XML data
	 */
	@GET 
	@Path("get/{userId}/{tradName}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTradition(	@PathParam("userId") String userId,
    								@PathParam("tradName") String traditionName)
	{
		System.out.println("Call for " + userId + " and " + traditionName);
        return Neo4JToGraphMLParser.parseNeo4J(userId,traditionName, DB_PATH);
    }

	
	 /**
     * Imports a tradition by given GraphML file and meta data
     *
     * @return String that will be returned as a text/plain response.
     * @throws XMLStreamException 
     */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("new")
    public Response create(
    					@FormDataParam("name") String name,
    					@FormDataParam("language") String language,
    					@FormDataParam("public") String is_public,
    					@FormDataParam("userId") String userId,
    					@FormDataParam("file") InputStream uploadedInputStream,
    					@FormDataParam("file") FormDataContentDisposition fileDetail) throws IOException, XMLStreamException {
      
    	if(!User.checkUserExists(userId))
    	{
    		return Response.status(Response.Status.CONFLICT).entity("Error: No user with this id exists").build();
    	}
    	
    	//Boolean is_public_bool = is_public.equals("on")? true : false;
    	String uploadedFileLocation = "upload/" + fileDetail.getFileName();
    	 
		// save it
		writeToFile(uploadedInputStream, uploadedFileLocation);
    	
		Response resp = GraphMLToNeo4JParser.parseGraphML(uploadedFileLocation, DB_PATH, userId, name.substring(0, 3));
		// The prefix will always be some sort of '12_', to make sure that all nodes are unique
		
		deleteFile(uploadedFileLocation);

    	return resp;
    }
    
    
    /**
     * Helper class for writing stream into a given location
     * @param uploadedInputStream
     * @param uploadedFileLocation
     */
 	private void writeToFile(InputStream uploadedInputStream,
 		String uploadedFileLocation) {
  
 		try {
 			
 			OutputStream out = new FileOutputStream(new File(
 					uploadedFileLocation));
 			int read = 0;
 			byte[] bytes = new byte[1024];
  
 			out = new FileOutputStream(new File(uploadedFileLocation));
 			while ((read = uploadedInputStream.read(bytes)) != -1) {
 				out.write(bytes, 0, read);
 			}
 			out.flush();
 			out.close();
 		} catch (IOException e) {
  
 			e.printStackTrace();
 		}
  
 	}
 	
 	/**
 	 * Helper class for deleting a file by given name
 	 * @param filename
 	 */
 	private void deleteFile(String filename)
 	{
 		File file = new File(filename);
 		file.delete();
 	}
}