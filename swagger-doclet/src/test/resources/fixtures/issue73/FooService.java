package fixtures.issue73;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.List;

@SuppressWarnings("javadoc")
@Path(Util.DEVICE_API_BASE_URL) //"/foo/{id: [0-1]+}")
public class FooService {
	public static final String BASE_URL = "/api";

	public static final String API_WORKSPACES_BASE_URL = ApiUtils.BASE_URL + WorkspacesEndpoint
		.WORKSPACE_PATH;

	public static final String DEVICE_API_BASE_URL = API_WORKSPACES_BASE_URL + "/devices";

	/**
	 * @resourcePath getMany
	 * @resource getMany
	 * @parentEndpointName getMany
	 * @responseType java.util.List<fixtures.issue73.FooSub>
	 */
	@GET
	@Path("/1")
	public List<Foo> getMany() {
		return null;
	}

}
