/*
 * Copyright (c) 2024 TUM Applied Education Technologies (AET)
 * Licensed under the MIT License
 */
package de.tum.cit.aet.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import org.openapitools.codegen.*;
import org.openapitools.codegen.languages.TypeScriptAngularClientCodegen;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom OpenAPI Generator for Angular 22+ with modern best practices.
 *
 * <p>This generator extends the default TypeScript Angular generator and produces
 * a clean, signal-based Angular client. It generates three types of files per API tag:</p>
 *
 * <ol>
 *   <li><b>API Service</b> ({@code *-api.ts}) &mdash; Injectable service with mutation methods (POST, PUT, DELETE)
 *       using {@code HttpClient} and the {@code inject()} function.</li>
 *   <li><b>API Resource</b> ({@code *-resources.ts}) &mdash; Signal-based {@code httpResource} wrappers
 *       for GET operations, enabling reactive data fetching. Only generated for tags that have GET operations.</li>
 *   <li><b>Model</b> ({@code *.ts}) &mdash; TypeScript interfaces with readonly properties,
 *       plus const enum objects for runtime enum access (e.g., {@code JobDetailDTOStateEnum.Draft}).</li>
 * </ol>
 *
 * <p><b>Generation Pipeline</b></p>
 * The generator hooks into four lifecycle stages of the OpenAPI Generator framework:
 * <ol>
 *   <li>{@link #processOpts()} &mdash; Reads CLI options and registers mustache templates.</li>
 *   <li>{@link #processOpenAPI(OpenAPI)} &mdash; Scans paths to determine which tags need resource files.</li>
 *   <li>{@link #postProcessAllModels(Map)} &mdash; Marks models as readonly or mutable.</li>
 *   <li>{@link #postProcessOperationsWithModels(OperationsMap, List)} &mdash; Splits operations,
 *       builds URL templates, and collects imports.</li>
 * </ol>
 *
 * <p><b>Naming Conventions</b></p>
 * <ul>
 *   <li>File names: kebab-case (e.g., {@code job-resource-api.ts}, {@code job-detail-dto.ts})</li>
 *   <li>Class names: PascalCase with {@code Api} suffix (e.g., {@code JobResourceApi})</li>
 *   <li>Operation IDs: camelCase, stripped of leading underscores and trailing digits</li>
 * </ul>
 */
public class Angular22Generator extends TypeScriptAngularClientCodegen {

    private static final Logger LOGGER = LoggerFactory.getLogger(Angular22Generator.class);

    /** TypeScript types that {@code String()} turns into a form field value without losing information. */
    private static final Set<String> TS_SCALAR_TYPES = Set.of("string", "number", "boolean");

    /** Local variables that every generated service method may declare next to its parameters. */
    private static final Set<String> SERVICE_LOCALS = Set.of("url", "queryParams", "queryString", "formData", "headers");
    /**
     * Identifiers that a generated resource function declares next to its path and header arguments. Query
     * parameters are properties of the {@code params} object there and cannot collide.
     */
    private static final Set<String> RESOURCE_LOCALS = Set.of("searchParams", "query", "params", "queryParams");
    /** An ASCII identifier, which TypeScript accepts as a property name without quotes. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /** Generator name used by the OpenAPI Generator SPI and CLI. */
    public static final String GENERATOR_NAME = "angular22";
    /** Config option for enabling httpResource-based GET resources. */
    public static final String USE_HTTP_RESOURCE = "useHttpResource";
    /** Config option for enabling inject() instead of constructor injection. */
    public static final String USE_INJECT_FUNCTION = "useInjectFunction";
    /** Config option for generating separate resource files for GET operations. */
    public static final String SEPARATE_RESOURCES = "separateResources";
    /** Config option for adding readonly modifiers to response models. */
    public static final String READONLY_MODELS = "readonlyModels";

    /** Whether to generate httpResource-based GET resources. */
    protected boolean useHttpResource = true;
    /** Whether to use Angular inject() for service dependencies. */
    protected boolean useInjectFunction = true;
    /** Whether to place GET resources in separate files. */
    protected boolean separateResources = true;
    /** Whether to add readonly modifiers to response models. */
    protected boolean readonlyModels = true;

    // =============================================================================================
    // 1) Constructor &mdash; Register templates, set naming conventions, define CLI options
    // =============================================================================================

    /**
     * Initializes the Angular 22 generator with custom templates, naming conventions, and CLI options.
     *
     * <p>Registers three template files:</p>
     * <ul>
     *   <li>{@code model.mustache} &rarr; model TypeScript files</li>
     *   <li>{@code api-service.mustache} &rarr; API service files ({@code *-api.ts})</li>
     *   <li>{@code api-resource.mustache} &rarr; httpResource files ({@code *-resources.ts}),
     *       conditionally added in {@link #processOpts()}</li>
     * </ul>
     */
    public Angular22Generator() {
        super();

        embeddedTemplateDir = templateDir = GENERATOR_NAME;
        outputFolder = "generated-code" + File.separator + GENERATOR_NAME;

        modelTemplateFiles.clear();
        modelTemplateFiles.put("model.mustache", ".ts");

        apiNameSuffix = "Api";
        // The parent strips serviceSuffix off every API class name to derive a file name, and fails on names shorter
        // than its default "Service" (e.g. FaqApi). Keep it equal to the suffix toApiName appends.
        serviceSuffix = apiNameSuffix;
        apiTemplateFiles.clear();
        apiTemplateFiles.put("api-service.mustache", "-api.ts");

        supportingFiles.clear();

        cliOptions.add(new CliOption(USE_HTTP_RESOURCE,
                "Use httpResource for GET requests (signal-based reactive fetching)")
                .defaultValue("true"));
        cliOptions.add(new CliOption(USE_INJECT_FUNCTION,
                "Use inject() function instead of constructor injection")
                .defaultValue("true"));
        cliOptions.add(new CliOption(SEPARATE_RESOURCES,
                "Generate separate resource files for GET operations")
                .defaultValue("true"));
        cliOptions.add(new CliOption(READONLY_MODELS,
                "Add readonly modifier to model properties")
                .defaultValue("true"));
    }

    /**
     * Returns the unique identifier for this generator, used by the CLI ({@code -g angular22}).
     *
     * @return the generator name ({@code "angular22"})
     */
    @Override
    public String getName() {
        return GENERATOR_NAME;
    }

    /**
     * Returns a human-readable description shown in the CLI help output.
     *
     * @return a description of this generator's capabilities
     */
    @Override
    public String getHelp() {
        return "Generates Angular 22 client code with modern best practices including " +
                "httpResource for GET requests, inject() function, and signal-based reactivity.";
    }

    // =============================================================================================
    // 2) processOpts &mdash; Read CLI options and conditionally register the resource template
    // =============================================================================================

    /**
     * Reads user-provided CLI options from {@code --additional-properties} and configures
     * the generator accordingly.
     *
     * <p>Each boolean option (useHttpResource, useInjectFunction, separateResources, readonlyModels)
     * defaults to {@code true} if not explicitly provided. When both {@code useHttpResource} and
     * {@code separateResources} are enabled, the {@code api-resource.mustache} template is registered
     * to generate signal-based httpResource wrapper files.</p>
     */
    @Override
    public void processOpts() {
        super.processOpts();

        supportingFiles.clear();

        if (additionalProperties.containsKey(USE_HTTP_RESOURCE)) {
            useHttpResource = Boolean.parseBoolean(additionalProperties.get(USE_HTTP_RESOURCE).toString());
        }
        additionalProperties.put(USE_HTTP_RESOURCE, useHttpResource);

        if (additionalProperties.containsKey(USE_INJECT_FUNCTION)) {
            useInjectFunction = Boolean.parseBoolean(additionalProperties.get(USE_INJECT_FUNCTION).toString());
        }
        additionalProperties.put(USE_INJECT_FUNCTION, useInjectFunction);

        if (additionalProperties.containsKey(SEPARATE_RESOURCES)) {
            separateResources = Boolean.parseBoolean(additionalProperties.get(SEPARATE_RESOURCES).toString());
        }
        additionalProperties.put(SEPARATE_RESOURCES, separateResources);

        if (additionalProperties.containsKey(READONLY_MODELS)) {
            readonlyModels = Boolean.parseBoolean(additionalProperties.get(READONLY_MODELS).toString());
        }
        additionalProperties.put(READONLY_MODELS, readonlyModels);

        if (useHttpResource && separateResources) {
            apiTemplateFiles.put("api-resource.mustache", "-resources.ts");
        }

        LOGGER.info("Angular22 Generator initialized with: useHttpResource={}, useInjectFunction={}, " +
                "separateResources={}, readonlyModels={}",
                useHttpResource, useInjectFunction, separateResources, readonlyModels);
    }

    // =============================================================================================
    // 3) processOpenAPI &mdash; Scan the spec to determine which tags need resource files
    // =============================================================================================

    /**
     * Scans all paths in the OpenAPI spec to classify each tag as having GET operations,
     * mutation operations, or both. Tags without any GET operations get their resource file
     * added to the generator's ignore list, since there is nothing to wrap in an httpResource.
     *
     * @param openAPI the parsed OpenAPI specification
     */
    @Override
    public void processOpenAPI(OpenAPI openAPI) {
        super.processOpenAPI(openAPI);

        if (openapiGeneratorIgnoreList == null) {
            openapiGeneratorIgnoreList = new HashSet<>();
        }

        Map<String, TagUsage> usageByTag = new HashMap<>();
        if (openAPI != null && openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, pathItem) -> {
                if (pathItem == null) {
                    return;
                }
                addOperationUsage(pathItem.getGet(), true, usageByTag);
                addOperationUsage(pathItem.getPost(), false, usageByTag);
                addOperationUsage(pathItem.getPut(), false, usageByTag);
                addOperationUsage(pathItem.getDelete(), false, usageByTag);
                addOperationUsage(pathItem.getPatch(), false, usageByTag);
                addOperationUsage(pathItem.getHead(), false, usageByTag);
                addOperationUsage(pathItem.getOptions(), false, usageByTag);
                addOperationUsage(pathItem.getTrace(), false, usageByTag);
            });
        }

        for (Map.Entry<String, TagUsage> entry : usageByTag.entrySet()) {
            String apiFilename = toApiFilename(entry.getKey());
            TagUsage usage = entry.getValue();
            if (!useHttpResource || !separateResources) {
                // No resource files when httpResource is disabled or resources are inline
                openapiGeneratorIgnoreList.add("api/" + apiFilename + "-resources.ts");
            } else if (!usage.hasGet) {
                // No resource file for tags without GET operations
                openapiGeneratorIgnoreList.add("api/" + apiFilename + "-resources.ts");
            }
            // Service files are always generated — they contain Observable methods for all operations
        }
    }

    /**
     * Records whether a single operation is a GET or a mutation for each of its tags.
     * Operations without tags are assigned to the "default" tag.
     *
     * @param operation  the OpenAPI operation to classify (may be {@code null})
     * @param isGet      {@code true} if this is a GET operation, {@code false} for mutations
     * @param usageByTag the map accumulating GET/mutation flags per tag
     */
    private void addOperationUsage(Operation operation, boolean isGet, Map<String, TagUsage> usageByTag) {
        if (operation == null) {
            return;
        }

        List<String> tags = operation.getTags();
        if (tags == null || tags.isEmpty()) {
            tags = Collections.singletonList("default");
        }
        for (String tag : tags) {
            String sanitizedTag = sanitizeTag(tag);
            TagUsage usage = usageByTag.computeIfAbsent(sanitizedTag, key -> new TagUsage());
            if (isGet) {
                usage.hasGet = true;
            } else {
                usage.hasMutation = true;
            }
        }
    }

    /** Tracks whether a given API tag has GET and/or mutation operations. */
    private static final class TagUsage {
        private boolean hasGet;
        private boolean hasMutation;
    }

    // =============================================================================================
    // 4) postProcessAllModels &mdash; Mark models as readonly or mutable
    // =============================================================================================

    /**
     * Post-processes all generated models to determine which should have readonly properties.
     *
     * <p>Models whose names end with {@code Create}, {@code Update}, {@code Request}, or {@code Input}
     * are considered input DTOs and get mutable properties. All other models are treated as output
     * DTOs and receive the {@code readonly} modifier on every property.</p>
     *
     * <p>The decision is passed to the mustache template via vendor extensions:</p>
     * <ul>
     *   <li>{@code x-is-input-dto} on the model &mdash; whether this is a mutable input DTO</li>
     *   <li>{@code x-is-readonly} on each property &mdash; whether to emit the {@code readonly} keyword</li>
     *   <li>{@code x-property-name} on each property &mdash; the property key, see {@link #toPropertyKey(String)}</li>
     * </ul>
     *
     * @param objs the map of all models, keyed by model name
     * @return the post-processed models map
     */
    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        Map<String, ModelsMap> result = super.postProcessAllModels(objs);

        for (ModelsMap modelsMap : result.values()) {
            for (ModelMap modelMap : modelsMap.getModels()) {
                CodegenModel model = modelMap.getModel();

                boolean isInputDto = model.name.endsWith("Create") ||
                        model.name.endsWith("Update") ||
                        model.name.endsWith("Request") ||
                        model.name.endsWith("Input");

                model.vendorExtensions.put("x-is-input-dto", isInputDto);
                model.vendorExtensions.put("x-use-readonly", readonlyModels && !isInputDto);

                for (CodegenProperty property : model.vars) {
                    property.vendorExtensions.put("x-is-readonly", readonlyModels && !isInputDto);
                    property.vendorExtensions.put("x-property-name", toPropertyKey(property.baseName));
                }
            }
        }

        return result;
    }

    // =============================================================================================
    // 5) postProcessOperationsWithModels &mdash; Split ops, build URL templates, collect imports
    // =============================================================================================

    /**
     * Post-processes all operations for a single API tag. This is the main processing step
     * that prepares the data consumed by the mustache templates.
     *
     * <p>Processing steps:</p>
     * <ol>
     *   <li>Save original OpenAPI paths before the parent class URL-encodes them</li>
     *   <li>Split operations into GET (for httpResource) and mutation (for HttpClient) lists</li>
     *   <li>Process path parameters &mdash; convert to camelCase, detect numeric types</li>
     *   <li>Process query parameters &mdash; generate a TypeScript params interface name</li>
     *   <li>Build TypeScript template literal URL paths from the original OpenAPI paths</li>
     *   <li>Collect all referenced model imports and map them to kebab-case filenames</li>
     * </ol>
     *
     * @param objs      the operations map for the current API tag
     * @param allModels all models available in the spec
     * @return the post-processed operations map with additional template data
     */
    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        // Step 1: Save original paths before super transforms them
        OperationMap operationsBefore = objs.getOperations();
        Map<String, String> originalPaths = new HashMap<>();
        for (CodegenOperation op : operationsBefore.getOperation()) {
            originalPaths.put(op.operationId, op.path);
        }

        OperationsMap result = super.postProcessOperationsWithModels(objs, allModels);

        // Each model must be imported from its own file. The default `imports` entries do not carry a
        // per-entry filename, so `{{classFilename}}` in the api templates falls through to the
        // enclosing API's filename and every model is (wrongly) imported from the same path. Compute
        // the correct model filename per import here.
        Object importsObj = result.get("imports");
        if (importsObj instanceof List<?> importsList) {
            for (Object item : importsList) {
                if (item instanceof Map<?, ?> rawImport) {
                    Object className = rawImport.get("classname");
                    if (className == null) {
                        className = rawImport.get("import");
                    }
                    if (className != null) {
                        String simpleName = className.toString();
                        simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mutableImport = (Map<String, Object>) rawImport;
                        mutableImport.put("classFilename", toModelFilename(simpleName));
                    }
                }
            }
        }

        OperationMap operations = result.getOperations();
        List<CodegenOperation> ops = operations.getOperation();

        // Step 2: Split into GETs (httpResource) and mutations (HttpClient)
        List<CodegenOperation> getOperations = new ArrayList<>();
        List<CodegenOperation> mutationOperations = new ArrayList<>();

        for (CodegenOperation op : ops) {
            op.vendorExtensions.put("x-use-inject", useInjectFunction);

            boolean isGet = "GET".equalsIgnoreCase(op.httpMethod);
            if (isGet) {
                op.vendorExtensions.put("x-is-get", true);
                op.vendorExtensions.put("x-use-http-resource", useHttpResource && separateResources);
                op.vendorExtensions.put("x-inline-resource", useHttpResource && !separateResources);
                getOperations.add(op);
            } else {
                op.vendorExtensions.put("x-is-get", false);
                op.vendorExtensions.put("x-is-mutation", true);
                mutationOperations.add(op);
            }

            // Non-JSON responses need an explicit Angular HttpClient responseType, for every HTTP method.
            // Without it the client defaults to responseType 'json' and tries to JSON.parse text/binary
            // payloads (e.g. iCalendar files, CSV exports, plain-text tokens, generated source code), which
            // throws at runtime. A binary (Blob) return becomes responseType 'blob'; a string return whose
            // produced media types are all text/* becomes responseType 'text'. JSON-string endpoints keep
            // the default parser. File downloads (isResponseFile) are handled in buildHttpCall with
            // observe: 'response' so callers get the HttpResponse headers.
            if ("Blob".equals(op.returnType)) {
                op.vendorExtensions.put("x-response-type", "blob");
            } else if ("string".equals(op.returnType) && producesTextOnly(op)) {
                op.vendorExtensions.put("x-response-type", "text");
            }

            // Step 3 & 4: Process parameters
            renameParametersCollidingWithLocals(op);
            processPathParameters(op);
            processQueryParameters(op);
            processHeaderParameters(op);
            processFormParameters(op);
            buildHttpCall(op);

            // Step 5: Build TypeScript template literal URLs
            String originalPath = originalPaths.getOrDefault(op.operationId, op.path);
            String pathTemplate = buildPathTemplate(op, originalPath, false);
            String resourcePathTemplate = buildPathTemplate(op, originalPath, true);
            op.vendorExtensions.put("xPathTemplate", pathTemplate);
            op.vendorExtensions.put("xResourcePathTemplate", resourcePathTemplate);
            if (pathTemplate != null && !pathTemplate.isBlank()) {
                op.path = pathTemplate;
            }
            if (isGet) {
                buildResourceFunction(op, resourcePathTemplate);
            }
        }

        operations.put("getOperations", getOperations);
        operations.put("mutationOperations", mutationOperations);
        operations.put("hasGetOperations", !getOperations.isEmpty());
        operations.put("hasMutationOperations", !mutationOperations.isEmpty());
        operations.put("hasInlineResources", useHttpResource && !separateResources && !getOperations.isEmpty());
        operations.put("hasServiceClass", !mutationOperations.isEmpty() || !getOperations.isEmpty());

        // Step 6: Collect model imports and map to kebab-case file paths
        Set<String> modelImports = new LinkedHashSet<>();
        for (CodegenOperation op : ops) {
            modelImports.addAll(op.imports);
        }
        List<Map<String, String>> tsImports = new ArrayList<>();
        for (String im : modelImports) {
            Map<String, String> tsImport = new HashMap<>();
            tsImport.put("classname", im);
            tsImport.put("filename", toModelFilename(im));
            tsImports.add(tsImport);
        }
        result.put("tsImports", tsImports);

        return result;
    }

    // =============================================================================================
    // Parameter Processing Helpers
    // =============================================================================================

    /**
     * Appends {@code Param} to TypeScript parameter names that the generated code also declares as local
     * variables (e.g. a query parameter {@code url} next to {@code const url = ...}), which would otherwise be a
     * duplicate identifier. Only {@code paramName} changes; the wire name ({@code baseName}) stays. Runs before
     * the path, query, header and form processing so every derived name uses the new one.
     *
     * @param op the operation whose parameters should be checked
     */
    private void renameParametersCollidingWithLocals(CodegenOperation op) {
        // The parameter lists hold copies of each parameter, so every list is renamed. The rule depends only on the
        // name and location, and a renamed name never collides again, so a parameter seen twice is renamed once.
        List<CodegenParameter> params = new ArrayList<>();
        for (List<CodegenParameter> list : Arrays.asList(op.allParams, op.bodyParams, op.pathParams, op.queryParams,
                op.headerParams, op.formParams, op.cookieParams, op.requiredParams, op.optionalParams,
                op.requiredAndNotNullableParams, op.notNullableParams)) {
            if (list != null) {
                params.addAll(list);
            }
        }
        if (op.bodyParam != null) {
            params.add(op.bodyParam);
        }
        for (CodegenParameter param : params) {
            boolean resourceIdentifier = param.isPathParam || param.isHeaderParam;
            if (SERVICE_LOCALS.contains(param.paramName) || (resourceIdentifier && RESOURCE_LOCALS.contains(param.paramName))) {
                param.paramName = param.paramName + "Param";
            }
        }
    }

    /**
     * Processes path parameters for a single operation: converts parameter names to camelCase
     * for TypeScript and detects numeric parameters (which don't need URI encoding).
     *
     * <p>Sets vendor extensions on each parameter:</p>
     * <ul>
     *   <li>{@code x-ts-name} &mdash; the camelCase TypeScript variable name</li>
     *   <li>{@code x-is-numeric} &mdash; whether the parameter is a number type</li>
     * </ul>
     *
     * @param op the operation whose path parameters should be processed
     */
    private void processPathParameters(CodegenOperation op) {
        if (op.pathParams != null) {
            for (CodegenParameter param : op.pathParams) {
                param.vendorExtensions.put("x-ts-name", toCamelCase(param.paramName));
                param.vendorExtensions.put("x-is-numeric", isNumericParam(param));
            }
        }
    }

    /**
     * Processes query parameters for a single operation: generates a TypeScript interface name
     * for the grouped query params and converts individual parameter names to camelCase.
     *
     * <p>Sets vendor extensions on the operation:</p>
     * <ul>
     *   <li>{@code x-has-query-params} &mdash; whether the operation has any query parameters</li>
     *   <li>{@code x-params-interface-name} &mdash; PascalCase interface name (e.g., {@code GetJobsParams})</li>
     * </ul>
     *
     * @param op the operation whose query parameters should be processed
     */
    private void processQueryParameters(CodegenOperation op) {
        if (op.queryParams != null && !op.queryParams.isEmpty()) {
            op.vendorExtensions.put("x-has-query-params", true);

            String paramsInterfaceName = toPascalCase(op.operationId) + "Params";
            op.vendorExtensions.put("x-params-interface-name", paramsInterfaceName);

            boolean allOptional = true;
            for (CodegenParameter param : op.queryParams) {
                param.vendorExtensions.put("x-ts-name", toCamelCase(param.paramName));
                if (param.required) {
                    allOptional = false;
                }
            }
            op.vendorExtensions.put("x-all-query-params-optional", allOptional);
        } else {
            op.vendorExtensions.put("x-has-query-params", false);
        }
    }

    /**
     * Builds the {@code HttpClient} call of a service method once, so the template prints a single
     * {@code return this.http.<method><typeArg>(<args>);} line.
     *
     * <p>Sets vendor extensions on the operation:</p>
     * <ul>
     *   <li>{@code x-http-type-arg} &mdash; the {@code <T>} type argument, empty when a {@code responseType}
     *       option selects a non-JSON overload that already fixes the result type</li>
     *   <li>{@code x-http-args} &mdash; the argument list: {@code url}, the payload for methods that take one,
     *       and an options object with, in this order and only when present, {@code body} (DELETE only),
     *       {@code headers}, {@code responseType} and {@code observe}</li>
     * </ul>
     *
     * @param op the operation whose HttpClient call should be built
     */
    private void buildHttpCall(CodegenOperation op) {
        String payload = null;
        if (op.getHasFormParams()) {
            payload = "formData";
        } else if (op.bodyParam != null) {
            payload = op.bodyParam.paramName;
        }

        List<String> args = new ArrayList<>();
        List<String> options = new ArrayList<>();
        args.add("url");
        if ("DELETE".equalsIgnoreCase(op.httpMethod)) {
            // HttpClient.delete(url, options) has no body argument; the body travels in the options.
            if (payload != null) {
                options.add("body: " + payload);
            }
        } else if (payload != null) {
            args.add(payload);
        } else if (op.isBodyAllowed()) {
            args.add("null");
        }
        if (op.getHasHeaderParams()) {
            options.add("headers");
        }

        Object responseType = op.vendorExtensions.get("x-response-type");
        if (op.isResponseFile) {
            options.add("responseType: 'blob'");
            options.add("observe: 'response'");
        } else if (responseType != null) {
            options.add("responseType: '" + responseType + "'");
        }
        if (!options.isEmpty()) {
            args.add("{ " + String.join(", ", options) + " }");
        }

        // The text and blob overloads return Observable<string> / Observable<Blob> (or HttpResponse<Blob>) and
        // take no type argument; the JSON overload is generic in the parsed body.
        boolean jsonResponse = !op.isResponseFile && responseType == null;
        String typeArg = jsonResponse ? "<" + (op.returnType != null ? op.returnType : "void") + ">" : "";
        op.vendorExtensions.put("x-http-type-arg", typeArg);
        op.vendorExtensions.put("x-http-args", String.join(", ", args));
    }

    /**
     * Processes header parameters for a single operation: sets the camelCase TypeScript variable name
     * ({@code x-ts-name}) that the resource template uses for the header argument.
     *
     * @param op the operation whose header parameters should be processed
     */
    private void processHeaderParameters(CodegenOperation op) {
        if (op.headerParams != null) {
            for (CodegenParameter param : op.headerParams) {
                param.vendorExtensions.put("x-ts-name", toCamelCase(param.paramName));
            }
        }
    }

    /**
     * Builds the parameter list and the request expression of a GET operation's httpResource function.
     *
     * <p>Sets vendor extensions on the operation:</p>
     * <ul>
     *   <li>{@code x-resource-params} &mdash; path parameters, then header parameters (each a signal or a plain
     *       value), then the query {@code params} signal. An argument is marked optional ({@code ?}) only when
     *       every argument after it is optional too; an optional argument before a required one accepts
     *       {@code undefined} instead.</li>
     *   <li>{@code x-resource-request} &mdash; what the request function returns: the URL template literal, or
     *       {@code { url: ..., headers }} when the operation declares header parameters</li>
     * </ul>
     *
     * @param op                   the GET operation
     * @param resourcePathTemplate the URL path with {@code ${...}} placeholders for the resource template
     */
    private void buildResourceFunction(CodegenOperation op, String resourcePathTemplate) {
        List<ResourceArg> args = new ArrayList<>();
        for (CodegenParameter param : op.pathParams) {
            args.add(new ResourceArg(param.vendorExtensions.get("x-ts-name").toString(), signalOrValue(param.dataType), false));
        }
        for (CodegenParameter param : op.headerParams) {
            args.add(new ResourceArg(param.vendorExtensions.get("x-ts-name").toString(), signalOrValue(param.dataType), !param.required));
        }
        boolean hasQueryParams = Boolean.TRUE.equals(op.vendorExtensions.get("x-has-query-params"));
        if (hasQueryParams) {
            args.add(new ResourceArg("params", "Signal<" + op.vendorExtensions.get("x-params-interface-name") + ">",
                    Boolean.TRUE.equals(op.vendorExtensions.get("x-all-query-params-optional"))));
        }

        LinkedList<String> rendered = new LinkedList<>();
        boolean trailingOptional = true;
        for (int i = args.size() - 1; i >= 0; i--) {
            ResourceArg arg = args.get(i);
            trailingOptional = trailingOptional && arg.optional();
            if (trailingOptional) {
                rendered.addFirst(arg.name() + "?: " + arg.type());
            } else {
                rendered.addFirst(arg.name() + ": " + arg.type() + (arg.optional() ? " | undefined" : ""));
            }
        }
        op.vendorExtensions.put("x-resource-params", String.join(", ", rendered));

        String url = "`${BASE_PATH}" + resourcePathTemplate + (hasQueryParams ? "${query ? `?${query}` : ''}" : "") + "`";
        op.vendorExtensions.put("x-resource-request", op.getHasHeaderParams() ? "{ url: " + url + ", headers }" : url);
    }

    /** One argument of a generated httpResource function. */
    private record ResourceArg(String name, String type, boolean optional) {
    }

    private static String signalOrValue(String dataType) {
        return "Signal<" + dataType + " | undefined> | " + dataType;
    }

    /**
     * Computes the {@code FormData.append} statement for each multipart field and stores it in the
     * {@code x-form-append} vendor extension.
     *
     * <p>{@code FormData} only accepts strings and Blobs. Binary fields are appended as they are (an array of
     * binaries as one part per item), scalars and enums are converted with {@code String()}, and everything
     * else (objects, arrays of objects, maps) goes out as a single {@code application/json} part, which is
     * what Spring's {@code @RequestPart} expects for a DTO.</p>
     *
     * @param op the operation whose form parameters should be processed
     */
    private void processFormParameters(CodegenOperation op) {
        if (op.formParams == null) {
            return;
        }
        for (CodegenParameter param : op.formParams) {
            String name = param.paramName;
            String key = "'" + param.baseName + "'";
            String statement;
            if (isBinaryType(param.dataType)) {
                statement = "formData.append(" + key + ", " + name + ");";
            } else if (param.isArray && isBinaryType(param.items != null ? param.items.dataType : null)) {
                statement = name + ".forEach(item => formData.append(" + key + ", item));";
            } else if (param.isEnum || param.isEnumRef || TS_SCALAR_TYPES.contains(param.dataType)) {
                statement = "formData.append(" + key + ", String(" + name + "));";
            } else {
                statement = "formData.append(" + key + ", new Blob([JSON.stringify(" + name + ")], { type: 'application/json' }));";
            }
            param.vendorExtensions.put("x-form-append", statement);
        }
    }

    private static boolean isBinaryType(String dataType) {
        return "Blob".equals(dataType) || "File".equals(dataType);
    }

    // =============================================================================================
    // URL Path Template Builder
    // =============================================================================================

    /**
     * Builds a TypeScript template literal URL from the original OpenAPI path by replacing
     * {@code {paramName}} placeholders with {@code ${variable}} expressions.
     *
     * <p>The variable naming depends on the context:</p>
     * <ul>
     *   <li><b>Service methods</b> ({@code useSignalValue=false}): string params use
     *       {@code paramPath} (URI-encoded via {@code encodeURIComponent}), numeric params
     *       use the raw variable name.</li>
     *   <li><b>httpResource methods</b> ({@code useSignalValue=true}): string params use
     *       {@code paramPath}, numeric params use {@code paramValue} (unwrapped from signals).</li>
     * </ul>
     *
     * @param op             the operation being processed
     * @param originalPath   the raw OpenAPI path before URL encoding (e.g., {@code /api/jobs/{id}/pdf})
     * @param useSignalValue {@code true} for httpResource templates, {@code false} for HttpClient services
     * @return the TypeScript template literal path (e.g., {@code /api/jobs/${idPath}/pdf}),
     *         or {@code null} if {@code originalPath} is {@code null}
     */
    private String buildPathTemplate(CodegenOperation op, String originalPath, boolean useSignalValue) {
        if (originalPath == null) {
            return null;
        }

        String path = originalPath;

        if (op.pathParams != null) {
            for (CodegenParameter param : op.pathParams) {
                Object tsName = param.vendorExtensions.get("x-ts-name");
                String baseName = tsName != null ? tsName.toString() : param.paramName;
                boolean isNumeric = Boolean.TRUE.equals(param.vendorExtensions.get("x-is-numeric"));

                String valueVar;
                if (useSignalValue) {
                    valueVar = isNumeric ? baseName + "Value" : baseName + "Path";
                } else {
                    valueVar = isNumeric ? baseName : baseName + "Path";
                }

                String placeholder = "{" + param.baseName + "}";
                path = path.replace(placeholder, "${" + valueVar + "}");
            }
        }

        return path;
    }

    // =============================================================================================
    // Naming Convention Overrides
    // =============================================================================================

    /**
     * Converts a model name to a kebab-case filename derived from its class name.
     *
     * <p>The generator calls this with the raw schema name when it writes a model file (e.g. the inline schema
     * {@code getExam_200_response}) and with the class name when it writes an import ({@code GetExam200Response}).
     * Going through {@link #toModelName(String)} first gives both the same file name.</p>
     *
     * @param name the schema or class name (e.g., {@code JobDetailDTO})
     * @return the kebab-case filename without extension (e.g., {@code job-detail-dto})
     */
    @Override
    public String toModelFilename(String name) {
        return toKebabCase(toModelName(name));
    }

    /**
     * Converts an API tag name to a kebab-case filename.
     *
     * @param name the API tag name (e.g., {@code JobResource})
     * @return the kebab-case filename without extension (e.g., {@code job-resource})
     */
    @Override
    public String toApiFilename(String name) {
        return toKebabCase(name);
    }

    /**
     * Converts an API tag name to a PascalCase class name with the {@code Api} suffix.
     *
     * @param name the API tag name (e.g., {@code job-resource})
     * @return the PascalCase class name (e.g., {@code JobResourceApi})
     */
    @Override
    public String toApiName(String name) {
        return StringUtils.camelize(name) + "Api";
    }

    /**
     * Cleans up operation IDs by stripping leading underscores and trailing digits
     * that the OpenAPI spec sometimes adds to disambiguate overloaded endpoints.
     *
     * @param operationId the raw operation ID from the OpenAPI spec
     * @return the cleaned operation ID, or {@code "operation"} if the result would be blank
     */
    @Override
    public String toOperationId(String operationId) {
        String name = super.toOperationId(operationId);
        String normalized = name.replaceFirst("^_+", "");
        normalized = normalized.replaceFirst("\\d+$", "");
        if (normalized.isBlank()) {
            normalized = "operation";
        }
        return normalized;
    }

    // =============================================================================================
    // String Conversion Utilities
    // =============================================================================================

    /**
     * Checks whether a parameter represents a numeric type (integer or number),
     * which determines whether it needs URI encoding in the generated URL template.
     *
     * @param param the codegen parameter to check
     * @return {@code true} if the parameter is numeric, {@code false} otherwise
     */
    private boolean isNumericParam(CodegenParameter param) {
        if (Boolean.TRUE.equals(param.isInteger) || Boolean.TRUE.equals(param.isNumber)) {
            return true;
        }
        return "number".equals(param.dataType) || "number".equals(param.baseType) || "integer".equals(param.baseType);
    }

    /**
     * Whether the operation only produces text media types (e.g. text/plain, text/calendar, text/csv).
     * Used to emit responseType: 'text' for string-returning operations; JSON-string endpoints (which produce
     * application/json) return false and keep the default JSON parser.
     */
    private boolean producesTextOnly(CodegenOperation op) {
        if (op.produces == null || op.produces.isEmpty()) {
            return false;
        }
        for (Map<String, String> mediaType : op.produces) {
            String type = mediaType.get("mediaType");
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("text/")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the TypeScript property key for a JSON key. Interfaces describe the JSON exactly, so the key is used as
     * it is: reserved words such as {@code final} are valid property names and stay unescaped, and a key that is not
     * an identifier (e.g. {@code x-y}) is quoted.
     *
     * @param jsonKey the property name in the JSON document
     * @return the key to print in the interface
     */
    private static String toPropertyKey(String jsonKey) {
        if (IDENTIFIER.matcher(jsonKey).matches()) {
            return jsonKey;
        }
        return "'" + jsonKey.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * Converts a PascalCase or camelCase string to kebab-case.
     *
     * @param name the input string (e.g., {@code "JobDetailDTO"})
     * @return the kebab-case result (e.g., {@code "job-detail-d-t-o"})
     */
    private String toKebabCase(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1-$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
                .replaceAll("_", "-")
                .toLowerCase();
    }

    /**
     * Converts a snake_case or kebab-case string to camelCase.
     *
     * @param name the input string (e.g., {@code "job_id"} or {@code "job-id"})
     * @return the camelCase result (e.g., {@code "jobId"}), or the input unchanged
     *         if it is {@code null} or empty
     */
    private String toCamelCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        Pattern pattern = Pattern.compile("[-_]([a-zA-Z0-9])");
        Matcher matcher = pattern.matcher(name);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, matcher.group(1).toUpperCase());
        }
        matcher.appendTail(buffer);
        String result = buffer.toString();
        return Character.toLowerCase(result.charAt(0)) + result.substring(1);
    }

    /**
     * Converts a string to PascalCase by capitalizing the first letter of the camelCase result.
     *
     * @param name the input string (e.g., {@code "get_jobs"})
     * @return the PascalCase result (e.g., {@code "GetJobs"}), or the input unchanged
     *         if it is {@code null} or empty
     */
    private String toPascalCase(String name) {
        String camel = toCamelCase(name);
        if (camel == null || camel.isEmpty()) {
            return camel;
        }
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    // =============================================================================================
    // Output Directory Configuration
    // =============================================================================================

    /**
     * Returns the generator type, which determines how the OpenAPI Generator CLI categorizes it.
     *
     * @return {@link CodegenType#CLIENT}
     */
    @Override
    public CodegenType getTag() {
        return CodegenType.CLIENT;
    }

    /**
     * Returns the output folder for generated API files.
     *
     * @return the path to the {@code api/} subdirectory within the output folder
     */
    @Override
    public String apiFileFolder() {
        return outputFolder + File.separator + "api";
    }

    /**
     * Returns the output folder for generated model files.
     *
     * @return the path to the {@code model/} subdirectory within the output folder
     */
    @Override
    public String modelFileFolder() {
        return outputFolder + File.separator + "model";
    }
}
