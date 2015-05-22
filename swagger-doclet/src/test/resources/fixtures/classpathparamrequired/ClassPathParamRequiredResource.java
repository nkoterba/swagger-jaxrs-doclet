package fixtures.classpathparamrequired;

import fixtures.paramrequired.*;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.DELETE;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

/**
 * The ClassPathParamRequiredResource represents a jaxrs resource that has has PathParams
 * defined in the Class path and then referenced in a constructor, field, task and then no tasks.
 * Each method in this Resource, however, should have a required field generated for it in JSON
 * for the taskId property.
 * @author nkoterba
 */
@Path("/api/{taskId: [0-9]+}")
@SuppressWarnings("javadoc")
public class ClassPathParamRequiredResource {

	@PathParam("taskId")
	private long _taskId;

	public ClassPathParamRequiredResource(@PathParam("taskId") long taskId) {
	}

	@GET
	public void getNothing(@PathParam("taskId") long taskId) {
	}

	@DELETE
	public void deleteNothing() {
	}

	@GET
	@Path("/{item: [A-Za-z0-9]+}")
	public String getItem(@PathParam("item") String item) {
		return item;
	}
}