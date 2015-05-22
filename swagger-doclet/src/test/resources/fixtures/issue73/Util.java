package fixtures.issue73;

public class Util {
	public static final String BASE_URL = "/api";

	public static final String API_WORKSPACES_BASE_URL =
		Util.BASE_URL + ("/workspaces/{workspaceId: [0-9]+}");

	public static final String DEVICE_API_BASE_URL = API_WORKSPACES_BASE_URL + "/devices";
}