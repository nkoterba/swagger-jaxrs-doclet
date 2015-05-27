package com.carma.swagger.doclet.parser;

import com.carma.swagger.doclet.DocletOptions;
import com.carma.swagger.doclet.model.*;
import com.carma.swagger.doclet.parser.ParserHelper.NumericTypeFilter;
import com.carma.swagger.doclet.translator.Translator;
import com.carma.swagger.doclet.translator.Translator.OptionalName;
import com.sun.javadoc.*;

import javax.ws.rs.core.MediaType;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Objects.firstNonNull;

/**
 * The ApiMethodParser represents a parser for resource methods
 *
 * @version $Id$
 */
public class ApiMethodParser {

	private static final Pattern GENERIC_RESPONSE_PATTERN = Pattern.compile("(.*)<(.*)>");

	// pattern that can match a code, a description and an optional response model type
	private static final Pattern[] RESPONSE_MESSAGE_PATTERNS = new Pattern[]{Pattern.compile("(\\d+)([^`]+)(`.*)?")};

	private Method parentMethod;
	private String parentPath;

	private final DocletOptions options;
	private final Translator translator;
	private final MethodDoc methodDoc;
	private final Set<Model> models;
	private final HttpMethod httpMethod;
	private final Collection<ClassDoc> classes; // model classes
	private final Collection<ClassDoc> typeClasses; // additional classes such as for primitives
	private final Collection<ClassDoc> allClasses; // merge of model and additional classes
	private final String classDefaultErrorType;
	private final String methodDefaultErrorType;

	/**
	 * This creates a ApiMethodParser
	 *
	 * @param options
	 * @param parentPath
	 * @param methodDoc
	 * @param classes
	 * @param typeClasses
	 * @param classDefaultErrorType
	 */
	public ApiMethodParser(DocletOptions options, String parentPath, MethodDoc methodDoc, Collection<ClassDoc> classes, Collection<ClassDoc> typeClasses,
	                       String classDefaultErrorType) {
		this.options = options;
		this.translator = options.getTranslator();
		this.parentPath = parentPath;
		this.methodDoc = methodDoc;
		this.models = new LinkedHashSet<Model>();
		this.httpMethod = ParserHelper.resolveMethodHttpMethod(methodDoc);
		this.parentMethod = null;
		this.classes = classes;
		this.typeClasses = typeClasses;
		this.classDefaultErrorType = classDefaultErrorType;
		this.methodDefaultErrorType = ParserHelper.getInheritableTagValue(methodDoc, options.getDefaultErrorTypeTags(), options);
		this.allClasses = new HashSet<ClassDoc>();
		if (classes != null) {
			this.allClasses.addAll(classes);
		}
		if (typeClasses != null) {
			this.allClasses.addAll(typeClasses);
		}
	}

	/**
	 * This creates a ApiMethodParser
	 *
	 * @param options
	 * @param parentMethod
	 * @param methodDoc
	 * @param classes
	 * @param typeClasses
	 * @param classDefaultErrorType
	 */
	public ApiMethodParser(DocletOptions options, Method parentMethod, MethodDoc methodDoc, Collection<ClassDoc> classes, Collection<ClassDoc> typeClasses,
	                       String classDefaultErrorType) {
		this(options, parentMethod.getPath(), methodDoc, classes, typeClasses, classDefaultErrorType);

		this.parentPath = parentMethod.getPath();
		this.parentMethod = parentMethod;

	}

	/**
	 * This parses a javadoc method doc and builds a pojo representation of it.
	 *
	 * @return The method with appropriate data set
	 */
	public Method parse() {
		String methodPath = ParserHelper.resolveMethodPath(this.methodDoc, this.options);
		if (this.httpMethod == null && methodPath.isEmpty()) {
			return null;
		}

		// check if deprecated and exclude if set to do so
		boolean deprecated = false;
		if (ParserHelper.isInheritableDeprecated(this.methodDoc, this.options)) {
			if (this.options.isExcludeDeprecatedOperations()) {
				return null;
			}
			deprecated = true;
		}

		// exclude if it has exclusion tags
		if (ParserHelper.hasInheritableTag(this.methodDoc, this.options.getExcludeOperationTags())) {
			return null;
		}

		String path = this.parentPath + methodPath;

		// build params
		List<ApiParameter> parameters = this.generateParameters();

		// build response messages
		List<ApiResponseMessage> responseMessages = generateResponseMessages();

		// ************************************
		// Return type
		// ************************************
		Type returnType = this.methodDoc.returnType();
		// first check if its a wrapper type and if so replace with the wrapped type
		returnType = firstNonNull(ApiModelParser.getReturnType(this.options, returnType), returnType);

		String returnTypeName = this.translator.typeName(returnType).value();
		Type modelType = returnType;

		ClassDoc[] viewClasses = ParserHelper.getInheritableJsonViews(this.methodDoc, this.options);

		// now see if it is a collection if so the return type will be array and the
		// containerOf will be added to the model

		String returnTypeItemsRef = null;
		String returnTypeItemsType = null;
		String returnTypeItemsFormat = null;
		Type containerOf = ParserHelper.getContainerType(returnType, null, this.allClasses);

		Map<String, Type> varsToTypes = new HashMap<String, Type>();

		// look for a custom return type, this is useful where we return a jaxrs Response in the method signature
		// but typically return a different object in its entity (such as for a 201 created response)
		String customReturnTypeName = ParserHelper.getInheritableTagValue(this.methodDoc, this.options.getResponseTypeTags(), this.options);
		NameToType nameToType = readCustomReturnType(customReturnTypeName, viewClasses);
		if (nameToType != null) {
			returnTypeName = nameToType.returnTypeName;
			returnType = nameToType.returnType;
			// set collection data
			if (nameToType.containerOf != null) {
				returnTypeName = "array";
				// its a model collection, add the container of type to the model
				modelType = nameToType.containerOf;
				returnTypeItemsRef = this.translator.typeName(nameToType.containerOf, viewClasses).value();
			} else if (nameToType.containerOfPrimitiveType != null) {
				returnTypeName = "array";
				// its a primitive collection
				returnTypeItemsType = nameToType.containerOfPrimitiveType;
				returnTypeItemsFormat = nameToType.containerOfPrimitiveTypeFormat;
			} else {
				modelType = returnType;
				if (nameToType.varsToTypes != null) {
					varsToTypes.putAll(nameToType.varsToTypes);
				}
			}
		} else if (containerOf != null) {
			returnTypeName = "array";
			// its a collection, add the container of type to the model
			modelType = containerOf;
			// set the items type or ref
			if (ParserHelper.isPrimitive(containerOf, this.options)) {
				OptionalName oName = this.translator.typeName(containerOf);
				returnTypeItemsType = oName.value();
				returnTypeItemsFormat = oName.getFormat();
			} else {
				returnTypeItemsRef = this.translator.typeName(containerOf, viewClasses).value();
			}

		} else {
			// if its not a container then adjust the return type name for any views
			returnTypeName = this.translator.typeName(returnType, viewClasses).value();

			// add parameterized types to the model
			// TODO: support variables e.g. for inherited or sub resources
			addParameterizedModelTypes(returnType, varsToTypes);
		}

		if (modelType != null && this.options.isParseModels()) {
			this.models.addAll(new ApiModelParser(this.options, this.translator, modelType, viewClasses).addVarsToTypes(varsToTypes).parse());
		}

		// ************************************
		// Summary and notes
		// ************************************
		// First Sentence of Javadoc method description
		String firstSentences = ParserHelper.getInheritableFirstSentenceTags(this.methodDoc);

		// default plugin behaviour
		String summary = firstSentences == null ? "" : firstSentences;
		String notes = ParserHelper.getInheritableCommentText(this.methodDoc);
		if (notes == null) {
			notes = "";
		}
		notes = notes.replace(summary, "").trim();

		// look for custom notes/summary tags to use instead
		String customNotes = ParserHelper.getInheritableTagValue(this.methodDoc, this.options.getOperationNotesTags(), this.options);
		if (customNotes != null) {
			notes = customNotes;
		}
		String customSummary = ParserHelper.getInheritableTagValue(this.methodDoc, this.options.getOperationSummaryTags(), this.options);
		if (customSummary != null) {
			summary = customSummary;
		}
		summary = this.options.replaceVars(summary);
		notes = this.options.replaceVars(notes);

		// Auth support
		OperationAuthorizations authorizations = generateAuthorizations();

		// ************************************
		// Produces & consumes
		// ************************************
		List<String> consumes = ParserHelper.getConsumes(this.methodDoc, this.options);
		List<String> produces = ParserHelper.getProduces(this.methodDoc, this.options);

		// final result!
		return new Method(this.httpMethod, this.methodDoc.name(), path, parameters, responseMessages, summary, notes, returnTypeName, returnTypeItemsRef,
			returnTypeItemsType, returnTypeItemsFormat, consumes, produces, authorizations, deprecated);
	}

	private OperationAuthorizations generateAuthorizations() {
		OperationAuthorizations authorizations = null;

		// build map of scopes from the api auth
		Map<String, Oauth2Scope> apiScopes = new HashMap<String, Oauth2Scope>();
		if (this.options.getApiAuthorizations() != null && this.options.getApiAuthorizations().getOauth2() != null
			&& this.options.getApiAuthorizations().getOauth2().getScopes() != null) {
			List<Oauth2Scope> scopes = this.options.getApiAuthorizations().getOauth2().getScopes();
			if (scopes != null) {
				for (Oauth2Scope scope : scopes) {
					apiScopes.put(scope.getScope(), scope);
				}
			}
		}
		// see if method has a tag that implies there is no authentication
		// in this case set the authentication object to {} to indicate we override
		// at the operation level
		// a) if method has an explicit unauth tag
		if (ParserHelper.hasInheritableTag(this.methodDoc, this.options.getUnauthOperationTags())) {
			authorizations = new OperationAuthorizations();
		} else {

			// otherwise if method has scope tags then add those to indicate method requires scope
			List<String> scopeValues = ParserHelper.getInheritableTagValues(this.methodDoc, this.options.getOperationScopeTags(), this.options);
			if (scopeValues != null) {
				List<Oauth2Scope> oauth2Scopes = new ArrayList<Oauth2Scope>();
				for (String scopeVal : scopeValues) {
					Oauth2Scope apiScope = apiScopes.get(scopeVal);
					if (apiScope == null) {
						throw new IllegalStateException("The scope: " + scopeVal + " was referenced in the method: " + this.methodDoc
							+ " but this scope was not part of the API service.json level authorization object.");
					}
					oauth2Scopes.add(apiScope);
				}
				authorizations = new OperationAuthorizations(oauth2Scopes);
			}

			// if not scopes see if its auth and whether we need to add default scope to it
			if (scopeValues == null || scopeValues.isEmpty()) {
				// b) if method has an auth tag that starts with one of the known values that indicates whether auth required.
				String authSpec = ParserHelper.getInheritableTagValue(this.methodDoc, this.options.getAuthOperationTags(), this.options);
				if (authSpec != null) {

					boolean unauthFound = false;
					for (String unauthValue : this.options.getUnauthOperationTagValues()) {
						if (authSpec.toLowerCase().startsWith(unauthValue.toLowerCase())) {
							authorizations = new OperationAuthorizations();
							unauthFound = true;
							break;
						}
					}
					if (!unauthFound) {
						// its deemed to require authentication, however there is no explicit scope so we need to use
						// the default scopes
						List<String> defaultScopes = this.options.getAuthOperationScopes();
						if (defaultScopes != null && !defaultScopes.isEmpty()) {
							List<Oauth2Scope> oauth2Scopes = new ArrayList<Oauth2Scope>();
							for (String scopeVal : defaultScopes) {
								Oauth2Scope apiScope = apiScopes.get(scopeVal);
								if (apiScope == null) {
									throw new IllegalStateException("The default scope: " + scopeVal + " needed for the authorized method: " + this.methodDoc
										+ " was not part of the API service.json level authorization object.");
								}
								oauth2Scopes.add(apiScope);
							}
							authorizations = new OperationAuthorizations(oauth2Scopes);
						}
					}
				}
			}

		}
		return authorizations;
	}

	private List<ApiResponseMessage> generateResponseMessages() {
		List<ApiResponseMessage> responseMessages = new LinkedList<ApiResponseMessage>();

		List<String> tagValues = ParserHelper.getInheritableTagValues(this.methodDoc, this.options.getResponseMessageTags(), this.options);
		if (tagValues != null) {
			for (String tagValue : tagValues) {
				for (Pattern pattern : RESPONSE_MESSAGE_PATTERNS) {
					Matcher matcher = pattern.matcher(tagValue);
					if (matcher.find()) {
						int statusCode = Integer.parseInt(matcher.group(1).trim());
						// trim special chars the desc may start with
						String desc = ParserHelper.trimLeadingChars(matcher.group(2), '|', '-');

						// see if it has a custom response model
						String responseModelClass = null;
						if (matcher.groupCount() > 2) {
							responseModelClass = ParserHelper.trimLeadingChars(matcher.group(3), '`');
						}
						// for errors, if no custom one use the method level one if there is one
						if (statusCode >= 400) {
							if (responseModelClass == null) {
								responseModelClass = this.methodDefaultErrorType;
							}
							// for errors, if no custom one use the class level one if there is one
							if (responseModelClass == null) {
								responseModelClass = this.classDefaultErrorType;
							}
						}

						String responseModel = null;
						if (responseModelClass != null) {
							Type responseType = ParserHelper.findModel(this.classes, responseModelClass);
							if (responseType != null) {
								responseModel = this.translator.typeName(responseType).value();
								if (this.options.isParseModels()) {
									this.models.addAll(new ApiModelParser(this.options, this.translator, responseType).parse());
								}
							}
						}

						responseMessages.add(new ApiResponseMessage(statusCode, desc, responseModel));
						break;
					}
				}
			}
		}

		// sort the response messages as required
		if (!responseMessages.isEmpty() && this.options.getResponseMessageSortMode() != null) {
			switch (this.options.getResponseMessageSortMode()) {
				case CODE_ASC:
					Collections.sort(responseMessages, new Comparator<ApiResponseMessage>() {

						public int compare(ApiResponseMessage o1, ApiResponseMessage o2) {
							return Integer.valueOf(o1.getCode()).compareTo(Integer.valueOf(o2.getCode()));
						}
					});
					break;
				case CODE_DESC:
					Collections.sort(responseMessages, new Comparator<ApiResponseMessage>() {

						public int compare(ApiResponseMessage o1, ApiResponseMessage o2) {
							return Integer.valueOf(o2.getCode()).compareTo(Integer.valueOf(o1.getCode()));
						}
					});
					break;
				case AS_APPEARS:
					// noop
					break;
				default:
					throw new UnsupportedOperationException("Unknown ResponseMessageSortMode: " + this.options.getResponseMessageSortMode());

			}
		}

		return responseMessages;
	}

	private List<ApiParameter> generateParameters() {
		// parameters
		List<ApiParameter> parameters = new LinkedList<ApiParameter>();

		// read whether the method consumes multipart
		List<String> consumes = ParserHelper.getConsumes(this.methodDoc, this.options);
		boolean consumesMultipart = consumes != null && consumes.contains(MediaType.MULTIPART_FORM_DATA);

		// get raw parameter names from method signature
		Set<String> rawParamNames = ParserHelper.getParamNames(this.methodDoc);

		// get full list including any beanparam parameter names
		Set<String> allParamNames = new HashSet<String>(rawParamNames);
		for (int paramIndex = 0; paramIndex < this.methodDoc.parameters().length; paramIndex++) {
			final Parameter parameter = ParserHelper.getParameterWithAnnotations(this.methodDoc, paramIndex);
			String paramCategory = ParserHelper.paramTypeOf(consumesMultipart, parameter, this.options);
			// see if its a special composite type e.g. @BeanParam
			if ("composite".equals(paramCategory)) {
				Type paramType = parameter.type();
				ApiModelParser modelParser = new ApiModelParser(this.options, this.translator, paramType, consumesMultipart, true);
				Set<Model> models = modelParser.parse();
				String rootModelId = modelParser.getRootModelId();
				for (Model model : models) {
					if (model.getId().equals(rootModelId)) {
						Map<String, Property> modelProps = model.getProperties();
						for (Map.Entry<String, Property> entry : modelProps.entrySet()) {
							Property property = entry.getValue();
							String rawFieldName = property.getRawFieldName();
							allParamNames.add(rawFieldName);
						}
					}
				}
			}
		}

		// read required and optional params
		Set<String> optionalParams = ParserHelper.getMatchingParams(this.methodDoc, allParamNames, this.options.getOptionalParamsTags(),
			this.options.getOptionalParamAnnotations(), this.options);

		Set<String> requiredParams = ParserHelper.getMatchingParams(this.methodDoc, allParamNames, this.options.getRequiredParamsTags(),
			this.options.getRequiredParamAnnotations(), this.options);

		// read exclude params
		List<String> excludeParams = ParserHelper.getCsvParams(this.methodDoc, allParamNames, this.options.getExcludeParamsTags(), this.options);

		// read csv params
		List<String> csvParams = ParserHelper.getCsvParams(this.methodDoc, allParamNames, this.options.getCsvParamsTags(), this.options);

		// read min and max values of params
		Map<String, String> paramMinVals = ParserHelper.getParameterValues(this.methodDoc, allParamNames, this.options.getParamsMinValueTags(),
			this.options.getParamMinValueAnnotations(), new NumericTypeFilter(this.options), this.options, new String[]{"value", "min"});
		Map<String, String> paramMaxVals = ParserHelper.getParameterValues(this.methodDoc, allParamNames, this.options.getParamsMaxValueTags(),
			this.options.getParamMaxValueAnnotations(), new NumericTypeFilter(this.options), this.options, new String[]{"value", "max"});

		// filter min/max params so they

		// read default values of params
		Map<String, String> paramDefaultVals = ParserHelper.getMethodParamNameValuePairs(this.methodDoc, allParamNames,
			this.options.getParamsDefaultValueTags(), this.options);

		// read override names of params
		Map<String, String> paramNames = ParserHelper.getMethodParamNameValuePairs(this.methodDoc, allParamNames, this.options.getParamsNameTags(),
			this.options);

		for (int paramIndex = 0; paramIndex < this.methodDoc.parameters().length; paramIndex++) {
			final Parameter parameter = ParserHelper.getParameterWithAnnotations(this.methodDoc, paramIndex);
			if (!shouldIncludeParameter(this.httpMethod, excludeParams, parameter)) {
				continue;
			}

			Type paramType = getParamType(parameter.type());
			String paramCategory = ParserHelper.paramTypeOf(consumesMultipart, parameter, this.options);
			String paramName = parameter.name();

			// see if its a special composite type e.g. @BeanParam
			if ("composite".equals(paramCategory)) {

				ApiModelParser modelParser = new ApiModelParser(this.options, this.translator, paramType, consumesMultipart, true);
				Set<Model> models = modelParser.parse();
				String rootModelId = modelParser.getRootModelId();
				for (Model model : models) {
					if (model.getId().equals(rootModelId)) {
						List<String> requiredFields = model.getRequiredFields();
						List<String> optionalFields = model.getOptionalFields();
						Map<String, Property> modelProps = model.getProperties();
						for (Map.Entry<String, Property> entry : modelProps.entrySet()) {
							Property property = entry.getValue();
							String renderedParamName = entry.getKey();
							String rawFieldName = property.getRawFieldName();

							Boolean allowMultiple = ParserHelper.getAllowMultiple(paramCategory, rawFieldName,
								csvParams);

							// see if there is a required javadoc tag directly on the bean param field, if so use that
							Boolean required = null;
							if (requiredFields != null && requiredFields.contains(renderedParamName)) {
								required = Boolean.TRUE;
							} else if (optionalFields != null && optionalFields.contains(renderedParamName)) {
								required = Boolean.FALSE;
							} else {
								required = getRequired(paramCategory, rawFieldName, property.getType(), optionalParams, requiredParams);
							}

							String itemsRef = property.getItems() == null ? null : property.getItems().getRef();
							String itemsType = property.getItems() == null ? null : property.getItems().getType();
							String itemsFormat = property.getItems() == null ? null : property.getItems().getFormat();

							ApiParameter param = new ApiParameter(property.getParamCategory(), renderedParamName, required, allowMultiple, property.getType(),
								property.getFormat(), property.getDescription(), itemsRef, itemsType, itemsFormat, property.getUniqueItems(),
								property.getAllowableValues(), property.getMinimum(), property.getMaximum(), property.getDefaultValue());

							parameters.add(param);
						}
						break;
					}
				}

				continue;
			}

			// look for a custom input type for body params
			if ("body".equals(paramCategory)) {
				String customParamType = ParserHelper.getInheritableTagValue(this.methodDoc, this.options.getInputTypeTags(), this.options);
				paramType = readCustomParamType(customParamType, paramType);
			}

			OptionalName paramTypeFormat = this.translator.parameterTypeName(consumesMultipart, parameter, paramType);
			String typeName = paramTypeFormat.value();
			String format = paramTypeFormat.getFormat();

			Boolean allowMultiple = null;
			List<String> allowableValues = null;
			String itemsRef = null;
			String itemsType = null;
			String itemsFormat = null;
			Boolean uniqueItems = null;
			String minimum = null;
			String maximum = null;
			String defaultVal = null;

			// set to form param type if data type is File
			if ("File".equals(typeName)) {
				paramCategory = "form";
			} else {

				Type containerOf = ParserHelper.getContainerType(paramType, null, this.allClasses);

				if (this.options.isParseModels()) {
					Type modelType = containerOf == null ? paramType : containerOf;
					this.models.addAll(new ApiModelParser(this.options, this.translator, modelType).parse());
				}

				// set enum values
				ClassDoc typeClassDoc = parameter.type().asClassDoc();
				allowableValues = ParserHelper.getAllowableValues(typeClassDoc);
				if (allowableValues != null) {
					typeName = "string";
				}

				// set whether its a csv param
				allowMultiple = ParserHelper.getAllowMultiple(paramCategory, paramName, csvParams);

				// get min and max param values
				minimum = paramMinVals.get(paramName);
				maximum = paramMaxVals.get(paramName);

				String validationContext = " for the method: " + this.methodDoc.name() + " parameter: " + paramName;

				// verify min max are numbers
				ParserHelper.verifyNumericValue(validationContext + " min value.", typeName, format, minimum);
				ParserHelper.verifyNumericValue(validationContext + " max value.", typeName, format, maximum);

				// get a default value, prioritize the jaxrs annotation
				// otherwise look for the javadoc tag
				defaultVal = ParserHelper.getDefaultValue(parameter, this.options);
				if (defaultVal == null) {
					defaultVal = paramDefaultVals.get(paramName);
				}

				// verify default vs min, max and by itself
				if (defaultVal != null) {
					if (minimum == null && maximum == null) {
						// just validate the default
						ParserHelper.verifyValue(validationContext + " default value.", typeName, format, defaultVal);
					}
					// if min/max then default is validated as part of comparison
					if (minimum != null) {
						int comparison = ParserHelper.compareNumericValues(validationContext + " min value.", typeName, format, defaultVal, minimum);
						if (comparison < 0) {
							throw new IllegalStateException("Invalid value for the default value of the method: " + this.methodDoc.name() + " parameter: "
								+ paramName + " it should be >= the minimum: " + minimum);
						}
					}
					if (maximum != null) {
						int comparison = ParserHelper.compareNumericValues(validationContext + " max value.", typeName, format, defaultVal, maximum);
						if (comparison > 0) {
							throw new IllegalStateException("Invalid value for the default value of the method: " + this.methodDoc.name() + " parameter: "
								+ paramName + " it should be <= the maximum: " + maximum);
						}
					}

					// if boolean then make lowercase
					if ("boolean".equalsIgnoreCase(typeName)) {
						defaultVal = defaultVal.toLowerCase();
					}
				}

				// if enum and default value check it matches the enum values
				if (allowableValues != null && defaultVal != null && !allowableValues.contains(defaultVal)) {
					throw new IllegalStateException("Invalid value: " + defaultVal + " for the default value of the method: " + this.methodDoc.name()
						+ " parameter: " + paramName + " it should be one of: " + allowableValues);
				}

				// set collection related fields
				// TODO: consider supporting parameterized collections as api parameters...
				if (containerOf != null) {
					OptionalName oName = this.translator.typeName(containerOf);
					if (ParserHelper.isPrimitive(containerOf, this.options)) {
						itemsType = oName.value();
						itemsFormat = oName.getFormat();
					} else {
						itemsRef = oName.value();
					}
				}

				if (typeName.equals("array")) {
					if (ParserHelper.isSet(paramType.qualifiedTypeName())) {
						uniqueItems = Boolean.TRUE;
					}
				}
			}

			// get whether required
			Boolean required = getRequired(paramCategory, paramName, typeName, optionalParams, requiredParams);

			// get the parameter name to use for the documentation
			String renderedParamName = ParserHelper.paramNameOf(parameter, paramNames, this.options.getParameterNameAnnotations(), this.options);

			// get description
			String description = this.options.replaceVars(ParserHelper.commentForParameter(this
					.methodDoc,
				parameter));

			// build parameter
			ApiParameter param = new ApiParameter(paramCategory, renderedParamName, required, allowMultiple, typeName, format, description, itemsRef,
				itemsType, itemsFormat, uniqueItems, allowableValues, minimum, maximum, defaultVal);

			parameters.add(param);
		}

		// parent method parameters are inherited
		if (this.parentMethod != null) {
			parameters.addAll(this.parentMethod.getParameters());
		}

		return parameters;
	}

	private Boolean getRequired(String paramCategory, String paramName, String typeName, Collection<String> optionalParams, Collection<String> requiredParams) {
		// set whether the parameter is required or not
		Boolean required = null;
		// if its a path param then its required as per swagger spec
		if ("path".equals(paramCategory)) {
			required = Boolean.TRUE;
		}
		// if its in the required list then its required
		else if (requiredParams.contains(paramName)) {
			required = Boolean.TRUE;
		}
		// else if its in the optional list its optional
		else if (optionalParams.contains(paramName)) {
			// leave as null as this is equivalent to false but doesn't add to the json
		}
		// else if its a body or File param its required
		else if ("body".equals(paramCategory) || ("File".equals(typeName) && "form".equals(paramCategory))) {
			required = Boolean.TRUE;
		}
		// otherwise its optional
		else {
			// leave as null as this is equivalent to false but doesn't add to the json
		}
		return required;
	}

	/**
	 * This gets the parsed models found for this method
	 *
	 * @return the set of parsed models found for this method
	 */
	public Set<Model> models() {
		return this.models;
	}

	private Type getParamType(Type type) {
		if (type != null) {
			ParameterizedType pt = type.asParameterizedType();
			if (pt != null) {
				Type[] typeArgs = pt.typeArguments();
				if (typeArgs != null && typeArgs.length > 0) {
					// if its a generic wrapper type then return the wrapped type
					if (this.options.getGenericWrapperTypes().contains(type.qualifiedTypeName())) {
						return typeArgs[0];
					}
				}
			}
		}
		return type;
	}

	private Type readCustomParamType(String customTypeName, Type defaultType) {
		if (customTypeName != null) {
			// lookup the type from the doclet classes
			Type customType = ParserHelper.findModel(this.classes, customTypeName);
			if (customType != null) {
				// also add this custom return type to the models
				if (this.options.isParseModels()) {
					this.models.addAll(new ApiModelParser(this.options, this.translator, customType).parse());
				}
				return customType;
			}
		}
		return defaultType;
	}

	static class NameToType {

		Type returnType;
		Type containerOf;
		String containerOfPrimitiveType;
		String containerOfPrimitiveTypeFormat;
		String returnTypeName;
		Map<String, Type> varsToTypes;
	}

	NameToType readCustomReturnType(String customTypeName, ClassDoc[] viewClasses) {
		if (customTypeName != null && customTypeName.trim().length() > 0) {
			customTypeName = customTypeName.trim();

			Type[] paramTypes = null;
			Type customType = null;

			// split it into container and container of, if its in the form X<Y>
			Matcher matcher = GENERIC_RESPONSE_PATTERN.matcher(customTypeName);
			if (matcher.find()) {
				customTypeName = matcher.group(1);
				if (ParserHelper.isCollection(customTypeName)) {
					String containerOfType = matcher.group(2);
					Type containerOf = null;
					String containerOfPrimitiveType = null;
					String containerOfPrimitiveTypeFormat = null;
					if (ParserHelper.isPrimitive(containerOfType, this.options)) {
						String[] typeFormat = ParserHelper.typeOf(containerOfType, this.options);
						containerOfPrimitiveType = typeFormat[0];
						containerOfPrimitiveTypeFormat = typeFormat[1];
					} else {
						containerOf = ParserHelper.findModel(this.classes, containerOfType);
						if (containerOf == null) {
							raiseCustomTypeNotFoundError(containerOfType);
						}
					}

					NameToType res = new NameToType();
					res.returnTypeName = ParserHelper.typeOf(customTypeName, this.options)[0];
					res.returnType = null;
					res.containerOf = containerOf;
					res.containerOfPrimitiveType = containerOfPrimitiveType;
					res.containerOfPrimitiveTypeFormat = containerOfPrimitiveTypeFormat;
					return res;
				} else if (ParserHelper.isMap(customTypeName)) {
					NameToType res = new NameToType();
					res.returnTypeName = ParserHelper.typeOf(customTypeName, this.options)[0];
					res.returnType = null;
					return res;
				} else {
					// its a parameterized type, add the parameterized classes to the model
					String[] paramTypeNames = matcher.group(2).split(",");
					paramTypes = new Type[paramTypeNames.length];
					int i = 0;
					for (String paramTypeName : paramTypeNames) {
						paramTypes[i] = ParserHelper.findModel(this.classes, paramTypeName);
						if (paramTypes[i] == null) {
							paramTypes[i] = ParserHelper.findModel(this.typeClasses, paramTypeName);
						}
						i++;
					}
				}
			}

			// lookup the type from the doclet classes
			customType = ParserHelper.findModel(this.allClasses, customTypeName);
			if (customType == null) {
				raiseCustomTypeNotFoundError(customTypeName);
			} else {
				customType = firstNonNull(ApiModelParser.getReturnType(this.options, customType), customType);

				// build map of var names to parameters if applicable
				Map<String, Type> varsToTypes = null;
				if (paramTypes != null) {
					varsToTypes = new HashMap<String, Type>();
					TypeVariable[] vars = customType.asClassDoc().typeParameters();
					int i = 0;
					for (TypeVariable var : vars) {
						varsToTypes.put(var.qualifiedTypeName(), paramTypes[i]);
						i++;
					}
					// add param types to the model
					for (Type type : paramTypes) {
						if (this.classes.contains(type)) {
							if (this.options.isParseModels()) {
								this.models.addAll(new ApiModelParser(this.options, this.translator, type).addVarsToTypes(varsToTypes).parse());
							}
						}
					}
				}

				String translated = this.translator.typeName(customType, viewClasses).value();
				if (translated != null) {
					NameToType res = new NameToType();
					res.returnTypeName = translated;
					res.returnType = customType;
					res.varsToTypes = varsToTypes;
					return res;
				}
			}
		}
		return null;
	}

	private void addParameterizedModelTypes(Type returnType, Map<String, Type> varsToTypes) {
		// TODO support variable types e.g. parameterize sub resources or inherited resources
		List<Type> parameterizedTypes = ParserHelper.getParameterizedTypes(returnType, varsToTypes);
		for (Type type : parameterizedTypes) {
			if (this.classes.contains(type)) {
				if (this.options.isParseModels()) {
					this.models.addAll(new ApiModelParser(this.options, this.translator, type).addVarsToTypes(varsToTypes).parse());
				}
			}
		}
	}

	private void raiseCustomTypeNotFoundError(String customType) {
		throw new IllegalStateException(
			"Could not find the source for the custom response class: "
				+ customType
				+ ". If it is not in the same project as the one you have added the doclet to, "
				+ "for example if it is in a dependent project then you should copy the source to the doclet calling project using the maven-dependency-plugin's unpack goal,"
				+ " and then add it to the source using the build-helper-maven-plugin's add-source goal.");
	}

	private boolean shouldIncludeParameter(HttpMethod httpMethod, List<String> excludeParams, Parameter parameter) {
		List<AnnotationDesc> allAnnotations = Arrays.asList(parameter.annotations());

		// remove any params annotated with exclude param annotations e.g. jaxrs Context
		if (ParserHelper.hasAnnotation(parameter, this.options.getExcludeParamAnnotations(), this.options)) {
			return false;
		}

		// remove any params with exclude param tags
		if (excludeParams != null && excludeParams.contains(parameter.name())) {
			return false;
		}

		// remove any deprecated params
		if (this.options.isExcludeDeprecatedParams() && ParserHelper.isDeprecated(parameter, this.options)) {
			return false;
		}

		// include if it has a jaxrs annotation
		if (ParserHelper.hasJaxRsAnnotation(parameter, this.options)) {
			return true;
		}

		// include if there are either no annotations or its a put/post/patch
		// this means for GET/HEAD/OPTIONS we don't include if it has some non jaxrs annotation on it
		return (allAnnotations.isEmpty() || httpMethod == HttpMethod.POST || httpMethod == HttpMethod.PUT || httpMethod == HttpMethod.PATCH);
	}

}
