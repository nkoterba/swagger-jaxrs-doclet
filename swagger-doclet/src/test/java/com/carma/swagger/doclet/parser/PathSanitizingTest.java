package com.carma.swagger.doclet.parser;

import junit.framework.TestCase;

/**
 * The PathSanitizingTest represents a test case of api and resource path sanitizing
 * @version $Id$
 * @author conor.roche
 */
public class PathSanitizingTest extends TestCase {

	/**
	 * This tests the api path sanitization
	 */
	public void testApiPath() {

		assertEquals("/api", ParserHelper.sanitizePath("/api"));
		assertEquals("/api/test", ParserHelper.sanitizePath("/api/test"));
		assertEquals("/api/test{id}", ParserHelper.sanitizePath("/api/test{id}"));
		assertEquals("/api/test/{id}", ParserHelper.sanitizePath("/api/test/{id}"));
		assertEquals("/api/test/{id}", ParserHelper.sanitizePath("/api/test/{id }"));
		assertEquals("/api/test/{id}", ParserHelper.sanitizePath("/api/test/{id:}"));
		assertEquals("/api/test/{id}", ParserHelper.sanitizePath("/api/test/{id : }"));
		assertEquals("/api/test/{id}", ParserHelper.sanitizePath("/api/test/{id :}"));
		assertEquals("/api/test/{id}", ParserHelper.sanitizePath("/api/test/{id:[0-9]+}"));
		assertEquals("/api/test/{id}", ParserHelper.sanitizePath("/api/test/{id: [0-9]+}"));

		assertEquals("/api/{workspace}/{id}", ParserHelper.sanitizePath("/api/{workspace: \\w+}/{id: " +
			"[0-9]+}"));
	}
}