package fixtures.regexpath;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

@SuppressWarnings("javadoc")
@Path("project/{id: [0-9]+}")
public class RegexPathResource {

	@GET
	public String getById(@PathParam("id") int id) {
		return null;
	}

}