package com.carma.swagger.doclet.parser;

import com.carma.swagger.doclet.DocletOptions;
import com.carma.swagger.doclet.model.*;
import com.carma.swagger.doclet.translator.Translator;
import com.google.common.base.Function;
import com.sun.javadoc.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.carma.swagger.doclet.parser.ParserHelper.parsePath;
import static com.google.common.base.Objects.equal;
import static com.google.common.base.Objects.firstNonNull;
import static com.google.common.collect.Maps.uniqueIndex;

/**
 * The CrossClassApiParser represents an api class parser that supports ApiDeclaration being
 * spread across multiple resource classes.
 *
 * @author conor.roche
 * @version $Id$
 */
public class CrossClassApiParser {

	private final DocletOptions options;
	private final ClassDoc classDoc;
	private final Collection<ClassDoc> classes;
	private final String rootPath;
	private final String swaggerVersion;
	private final String apiVersion;
	private final String basePath;

	private final Method parentMethod;
	private final Map<Type, ClassDoc> subResourceClasses;
	private final Collection<ClassDoc> typeClasses;

	private final Pattern classPathParamPattern = Pattern.compile("\\{(.*?)\\}");

	/**
	 * This creates a CrossClassApiParser for top level parsing
	 *
	 * @param options            The options for parsing
	 * @param classDoc           The class doc
	 * @param classes            The doclet classes to document
	 * @param typeClasses        Extra type classes that can be used as generic parameters
	 * @param subResourceClasses Sub resource doclet classes
	 * @param swaggerVersion     Swagger version
	 * @param apiVersion         Overall API version
	 * @param basePath           Overall base path
	 */
	public CrossClassApiParser(DocletOptions options, ClassDoc classDoc, Collection<ClassDoc> classes, Map<Type, ClassDoc> subResourceClasses,
	                           Collection<ClassDoc> typeClasses, String swaggerVersion, String apiVersion, String basePath) {
		super();
		this.options = options;
		this.classDoc = classDoc;
		this.classes = classes;
		this.typeClasses = typeClasses;
		this.subResourceClasses = subResourceClasses;
		this.rootPath = firstNonNull(parsePath(classDoc, options), "");
		this.swaggerVersion = swaggerVersion;
		this.apiVersion = apiVersion;
		this.basePath = basePath;
		this.parentMethod = null;
	}

	/**
	 * This creates a CrossClassApiParser for parsing a subresource
	 *
	 * @param options            The options for parsing
	 * @param classDoc           The class doc
	 * @param classes            The doclet classes to document
	 * @param typeClasses        Extra type classes that can be used as generic parameters
	 * @param subResourceClasses Sub resource doclet classes
	 * @param swaggerVersion     Swagger version
	 * @param apiVersion         Overall API version
	 * @param basePath           Overall base path
	 * @param parentMethod       The parent method that "owns" this sub resource
	 * @param parentResourcePath The parent resource path
	 */
	public CrossClassApiParser(DocletOptions options, ClassDoc classDoc, Collection<ClassDoc> classes, Map<Type, ClassDoc> subResourceClasses,
	                           Collection<ClassDoc> typeClasses, String swaggerVersion, String apiVersion, String basePath, Method parentMethod, String parentResourcePath) {
		super();
		this.options = options;
		this.classDoc = classDoc;
		this.classes = classes;
		this.typeClasses = typeClasses;
		this.subResourceClasses = subResourceClasses;
		this.rootPath = parentResourcePath + firstNonNull(parsePath(classDoc, options), "");
		this.swaggerVersion = swaggerVersion;
		this.apiVersion = apiVersion;
		this.basePath = basePath;
		this.parentMethod = parentMethod;
	}

	/**
	 * This gets the root jaxrs path of the api resource class
	 *
	 * @return The root path
	 */
	public String getRootPath() {
		return this.rootPath;
	}

	/**
	 * This parses the api declarations from the resource classes of the api
	 *
	 * @param declarations The map of resource name to declaration which will be added to
	 */
	public void parse(Map<String, ApiDeclaration> declarations) {

		ClassDoc currentClassDoc = this.classDoc;
		while (currentClassDoc != null) {

			// read default error type for class
			String defaultErrorTypeClass = ParserHelper.getTagValue(currentClassDoc, this.options.getDefaultErrorTypeTags(), this.options);
			Type defaultErrorType = ParserHelper.findModel(this.classes, defaultErrorTypeClass);

			Set<Model> classModels = new HashSet<Model>();
			if (this.options.isParseModels() && defaultErrorType != null) {
				classModels.addAll(new ApiModelParser(this.options, this.options.getTranslator(), defaultErrorType).parse());
			}

			// read class level resource path, priority and description
			String classResourcePath = ParserHelper.getTagValue(currentClassDoc, this.options.getResourceTags(), this.options);
			String classResourcePriority = ParserHelper.getTagValue(currentClassDoc, this.options.getResourcePriorityTags(), this.options);
			String classResourceDescription = ParserHelper.getTagValue(currentClassDoc, this.options.getResourceDescriptionTags(), this.options);

			// check if its a sub resource
			boolean isSubResourceClass = this.subResourceClasses != null && this.subResourceClasses.values().contains(currentClassDoc);

			// dont process a subresource outside the context of its parent method
			if (isSubResourceClass && this.parentMethod == null) {
				// skip
			} else {
				for (MethodDoc method : currentClassDoc.methods()) {
					ApiMethodParser methodParser = this.parentMethod == null ? new ApiMethodParser(this.options, this.rootPath, method, this.classes,
						this.typeClasses, defaultErrorTypeClass) : new ApiMethodParser(this.options, this.parentMethod, method, this.classes,
						this.typeClasses, defaultErrorTypeClass);

					Method parsedMethod = methodParser.parse();
					if (parsedMethod == null) {
						continue;
					}

					// see which resource path to use for the method, if its got a resourceTag then use that
					// otherwise use the root path
					String resourcePath = buildResourcePath(classResourcePath, method);

					if (parsedMethod.isSubResource()) {
						ClassDoc subResourceClassDoc = ParserHelper.lookUpClassDoc(method.returnType(), this.classes);
						if (subResourceClassDoc != null) {
							// delete class from the dictionary to handle recursive sub-resources
							Collection<ClassDoc> shrunkClasses = new ArrayList<ClassDoc>(this.classes);
							shrunkClasses.remove(currentClassDoc);
							// recursively parse the sub-resource class
							CrossClassApiParser subResourceParser = new CrossClassApiParser(this.options, subResourceClassDoc, shrunkClasses,
								this.subResourceClasses, this.typeClasses, this.swaggerVersion, this.apiVersion, this.basePath, parsedMethod, resourcePath);
							subResourceParser.parse(declarations);
						}
						continue;
					}

					ApiDeclaration declaration = declarations.get(resourcePath);
					if (declaration == null) {
						declaration = new ApiDeclaration(this.swaggerVersion, this.apiVersion, this.basePath, resourcePath, null, null, Integer.MAX_VALUE, null);
						declaration.setApis(new ArrayList<Api>());
						declaration.setModels(new HashMap<String, Model>());
						declarations.put(resourcePath, declaration);
					}

					// look for a priority tag for the resource listing and set on the resource if the resource hasn't had one set
					setApiPriority(classResourcePriority, method, currentClassDoc, declaration);

					// look for a method level description tag for the resource listing and set on the resource if the resource hasn't had one set
					setApiDeclarationDescription(classResourceDescription, method, declaration);

// find api this method should be added to
addMethod(method, parsedMethod, declaration);

// ------------ START CODE UPDATE/HACK -------------

// Check class constructors for @PathParam presence
for (ConstructorDoc c : currentClassDoc.constructors()) {

// get raw parameter names from 'constructor' signature
// TODO: THis is a hack as I wasn't sure how to get this mapping for constructors
Map<String, String> paramNames = new HashMap<String, String>();

for (String paramName : ParserHelper.getParamNames(c))
	paramNames.put(paramName, paramName);
// END HACK

for (Parameter p : c.parameters()) {
	String paramCategory = ParserHelper.paramTypeOf(false, p, this.options);

	if (paramCategory.equals("path")) { // TODO: Some way to not use string here?
		Type paramType = ParserHelper.getParamType(this.options, p.type());

		String renderedParamName = ParserHelper.paramNameOf(p, paramNames, this.options
			.getParameterNameAnnotations(), this.options);

		// Always required as a Class-level path param
		boolean required = true;

		// Assuming constructor @PathParams will never consume multipart
		boolean consumesMultipart = false;

		Translator.OptionalName paramTypeFormat = this.options.getTranslator()
			.parameterTypeName
				(consumesMultipart, p, paramType);
		String typeName = paramTypeFormat.value();
		String format = paramTypeFormat.getFormat();

		// get description
		String description = this.options.replaceVars(ParserHelper.commentForParameter(c,
			p));

		Boolean allowMultiple = null;
		List<String> allowableValues = null;
		String itemsRef = null;
		String itemsType = null;
		String itemsFormat = null;
		Boolean uniqueItems = null;
		String minimum = null;
		String maximum = null;
		String defaultVal = null;

		ApiParameter param = new ApiParameter(paramCategory,
			renderedParamName,
			required,
			allowMultiple,
			typeName,
			format,
			description,
			itemsRef,
			itemsType,
			itemsFormat,
			uniqueItems,
			allowableValues,
			minimum,
			maximum,
			defaultVal);

		// Create a param

		for (Api api : declaration.getApis()) {
			for (Operation op : api.getOperations()) {
				if (!op.getParameters().contains(param)) // This seems to work ok even if
				// @PathParam exists on methods directly
					op.getParameters().add(param);
			}
		}
	}
}

// Now check class fields for @PathParam presence
for (FieldDoc f : currentClassDoc.fields()) {
	for (AnnotationDesc annot : f.annotations()) {
		for(AnnotationDesc.ElementValuePair pair : annot.elementValues()) {
			AnnotationValue value = pair.value();
			AnnotationTypeElementDoc doc = pair.element();
			if (doc.toString().startsWith("javax.ws.rs.PathParam")) {
				String paramCategory = "path";

				String renderedParamName = value.value().toString();

				boolean required = true;
				Boolean allowMultiple = null;
				boolean consumesMultipart = false;

				Type fieldType = f.type();

				// Ok to pass null b/c consumesMultipart is false
				Translator.OptionalName paramTypeFormat = this.options.getTranslator()
					.parameterTypeName(consumesMultipart, null, fieldType);
				String typeName = paramTypeFormat.value();
				String format = paramTypeFormat.getFormat();

				// get description
				String description = this.options.replaceVars(f.commentText());

				List<String> allowableValues = null;
				String itemsRef = null;
				String itemsType = null;
				String itemsFormat = null;
				Boolean uniqueItems = null;
				String minimum = null;
				String maximum = null;
				String defaultVal = null;

				ApiParameter param = new ApiParameter(paramCategory,
					renderedParamName,
					required,
					allowMultiple,
					typeName,
					format,
					description,
					itemsRef,
					itemsType,
					itemsFormat,
					uniqueItems,
					allowableValues,
					minimum,
					maximum,
					defaultVal);

				// Create a param

				for (Api api : declaration.getApis()) {
					for (Operation op : api.getOperations()) {
						if (!op.getParameters().contains(param))
							op.getParameters().add(param);
					}
				}
			}
		}
	}
}

// Finally, add any Class-based @PathParams that were not on constructors or fields
// TODO: Handle all the customization this library supports with over-writing/renaming/etc
if (this.rootPath != null && !this.rootPath.isEmpty() && this.rootPath.contains("{")) {
	List<String> parentPathParams = new ArrayList<>();

	// Extract all the @PathParam from the rootPath using Regex
	Matcher m = classPathParamPattern.matcher(this.rootPath);
	while (m.find())
		parentPathParams.add(m.group(1));

	for (String pathParam : parentPathParams) {
		ApiParameter parameter = new ApiParameter(
			"path",
			pathParam,
			true,
			null,
			"string", // TODO: Use possible regex expressions to determine number vs string?,
			null,
			null,
			null,
			null,
			null,
			null,
			null, // TODO: Use Regex to generate a list of allowable values?
			null,
			null,
			null
		);

		for (Api api : declaration.getApis()) {
			for (Operation op : api.getOperations()) {
				boolean addParam = true;

				for (ApiParameter p : op.getParameters()) {
					if (p.getName().equals(parameter.getName()))
						addParam = false;
				}

				if (addParam)
					op.getParameters().add(parameter);
			}
		}
	}
}

// ------------ END CODE UPDATE/HACK -------------

// add models
Set<Model> methodModels = methodParser.models();
Map<String, Model> idToModels = addApiModels(classModels, methodModels, method);
declaration.getModels().putAll(idToModels);
					}
				}
				currentClassDoc = currentClassDoc.superclass();
				// ignore parent object class
				if (!ParserHelper.hasAncestor(currentClassDoc)) {
					break;
				}
			}
		}
	}

	private String buildResourcePath(String classResourcePath, MethodDoc method) {
		String resourcePath = getRootPath();
		if (classResourcePath != null) {
			resourcePath = classResourcePath;
		}

		if (this.options.getResourceTags() != null) {
			for (String resourceTag : this.options.getResourceTags()) {
				Tag[] tags = method.tags(resourceTag);
				if (tags != null && tags.length > 0) {
					resourcePath = tags[0].text();
					resourcePath = resourcePath.toLowerCase();
					resourcePath = resourcePath.trim().replace(" ", "_");
					break;
				}
			}
		}

		// sanitize the path and ensure it starts with /
		if (resourcePath != null) {
			resourcePath = ParserHelper.sanitizePath(resourcePath);

			if (!resourcePath.startsWith("/")) {
				resourcePath = "/" + resourcePath;
			}
		}


		return resourcePath;
	}

	private Map<String, Model> addApiModels(Set<Model> classModels, Set<Model> methodModels, MethodDoc method) {
		methodModels.addAll(classModels);
		Map<String, Model> idToModels = Collections.emptyMap();
		try {
			idToModels = uniqueIndex(methodModels, new Function<Model, String>() {

				public String apply(Model model) {
					return model.getId();
				}
			});
		} catch (Exception ex) {
			throw new IllegalStateException("dupe models, method : " + method + ", models: " + methodModels, ex);
		}
		return idToModels;
	}

	private void setApiPriority(String classResourcePriority, MethodDoc method, ClassDoc currentClassDoc, ApiDeclaration declaration) {
		int priorityVal = Integer.MAX_VALUE;
		String priority = ParserHelper.getInheritableTagValue(method, this.options.getResourcePriorityTags(), this.options);
		if (priority != null) {
			priorityVal = Integer.parseInt(priority);
		} else if (classResourcePriority != null) {
			// set from the class
			priorityVal = Integer.parseInt(classResourcePriority);
		}

		if (priorityVal != Integer.MAX_VALUE && declaration.getPriority() == Integer.MAX_VALUE) {
			declaration.setPriority(priorityVal);
		}
	}

	private void setApiDeclarationDescription(String classResourceDescription, MethodDoc method, ApiDeclaration declaration) {
		String description = ParserHelper.getInheritableTagValue(method, this.options.getResourceDescriptionTags(), this.options);
		if (description == null) {
			description = classResourceDescription;
		}
		if (description != null && declaration.getDescription() == null) {
			declaration.setDescription(this.options.replaceVars(description));
		}
	}

	private void addMethod(MethodDoc method, Method parsedMethod, ApiDeclaration declaration) {
		Api methodApi = null;
		for (Api api : declaration.getApis()) {
			if (parsedMethod.getPath().equals(api.getPath())) {
				methodApi = api;
				break;
			}
		}

		// read api level description
		String apiDescription = ParserHelper.getInheritableTagValue(method, this.options.getApiDescriptionTags(), this.options);

		if (methodApi == null) {
			methodApi = new Api(parsedMethod.getPath(), this.options.replaceVars(apiDescription), new ArrayList<Operation>());
			declaration.getApis().add(methodApi);
		} else if (methodApi.getDescription() == null && apiDescription != null) {
			methodApi.setDescription(apiDescription);
		}

		boolean alreadyAdded = false;
		// skip already added declarations
		for (Operation operation : methodApi.getOperations()) {
			boolean opParamsEmptyOrNull = operation.getParameters() == null || operation.getParameters().isEmpty();
			boolean parsedParamsEmptyOrNull = parsedMethod.getParameters() == null || parsedMethod.getParameters().isEmpty();
			if (operation.getMethod().equals(parsedMethod.getMethod())
				&& ((parsedParamsEmptyOrNull && opParamsEmptyOrNull) || (!opParamsEmptyOrNull && !parsedParamsEmptyOrNull && operation.getParameters()
				.size() == parsedMethod.getParameters().size())) && equal(operation.getNickname(), parsedMethod.getMethodName())) {
				alreadyAdded = true;
			}
		}
		if (!alreadyAdded) {
			methodApi.getOperations().add(new Operation(parsedMethod));
		}
	}

}
