/*
 * Copyright (c) 2024 TUM Applied Education Technologies (AET)
 * Licensed under the MIT License
 */
package de.tum.cit.aet.openapi;

import io.swagger.v3.oas.models.OpenAPI;
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
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAPI Generator for Angular 21 with modern best practices:
 * <ul>
 *   <li>Signal-based httpResource for selected GET requests</li>
 *   <li>Injectable services with inject() function for mutations</li>
 *   <li>Standalone services (providedIn: 'root')</li>
 *   <li>Strict TypeScript with readonly modifiers</li>
 * </ul>
 *
 * @author TUM AET
 */
public class Angular21Generator extends TypeScriptAngularClientCodegen {

    private static final Logger LOGGER = LoggerFactory.getLogger(Angular21Generator.class);

    /** Generator name used by the OpenAPI Generator SPI and CLI. */
    public static final String GENERATOR_NAME = "angular21";
    /** Config option for enabling inject() instead of constructor injection. */
    public static final String USE_INJECT_FUNCTION = "useInjectFunction";
    /** Config option for generating separate resource files for GET operations. */
    public static final String SEPARATE_RESOURCES = "separateResources";
    /** Config option for adding readonly modifiers to response models. */
    public static final String READONLY_MODELS = "readonlyModels";
    /** Config option for selecting GET operations that should be generated as httpResource methods. */
    public static final String HTTP_RESOURCE_OPERATIONS = "httpResourceOperations";
    /** OpenAPI operation vendor extension for selecting httpResource generation. */
    public static final String HTTP_RESOURCE_VENDOR_EXTENSION = "x-angular-http-resource";
    /** Config option for preserving the existing TypeScript Angular file and class names. */
    public static final String LEGACY_NAMING = "legacyNaming";

    /** Whether to use Angular inject() for service dependencies. */
    protected boolean useInjectFunction = true;
    /** Whether to place GET resources in separate files. */
    protected boolean separateResources = true;
    /** Whether to add readonly modifiers to response models. */
    protected boolean readonlyModels = true;
    /** Operation IDs that should be generated as httpResource methods. */
    protected Set<String> httpResourceOperations = new HashSet<>();
    /** Whether to preserve the existing TypeScript Angular generated names. */
    protected boolean legacyNaming = true;

    /** Creates a configured Angular 21 generator with default options. */
    public Angular21Generator() {
        super();

        // Override template directory
        embeddedTemplateDir = templateDir = GENERATOR_NAME;

        // Set output folder structure
        outputFolder = "generated-code" + File.separator + GENERATOR_NAME;

        // Configure model and API naming
        modelTemplateFiles.clear();
        modelTemplateFiles.put("model.mustache", ".ts");

        serviceSuffix = "ApiService";
        serviceFileSuffix = "Api.service";
        fileNaming = "camelCase";
        apiTemplateFiles.clear();
        apiTemplateFiles.put("api-service.mustache", ".ts");

        // Add resource templates for GET operations
        supportingFiles.clear();

        // CLI options
        cliOptions.add(new CliOption(USE_INJECT_FUNCTION,
                "Use inject() function instead of constructor injection")
                .defaultValue("true"));
        cliOptions.add(new CliOption(SEPARATE_RESOURCES,
                "Generate separate resource files for GET operations")
                .defaultValue("true"));
        cliOptions.add(new CliOption(READONLY_MODELS,
                "Add readonly modifier to model properties")
                .defaultValue("true"));
        cliOptions.add(new CliOption(HTTP_RESOURCE_OPERATIONS,
                "Comma-separated GET operationIds generated as httpResource methods. GET operations not listed are generated as Observable methods."));
        cliOptions.add(new CliOption(LEGACY_NAMING,
                "Preserve existing TypeScript Angular file and class names")
                .defaultValue("true"));
    }

    @Override
    public String getName() {
        return GENERATOR_NAME;
    }

    @Override
    public String getHelp() {
        return "Generates Angular 21 client code with modern best practices including " +
                "httpResource for selected GET requests, inject() function, and signal-based reactivity.";
    }

    @Override
    public void processOpts() {
        super.processOpts();

        // Replace base generator supporting files with the small set this template uses.
        supportingFiles.clear();
        supportingFiles.add(new SupportingFile("configuration.mustache", "", "configuration.ts"));

        // Process custom options
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

        if (additionalProperties.containsKey(HTTP_RESOURCE_OPERATIONS)) {
            httpResourceOperations = parseOperationIdSet(additionalProperties.get(HTTP_RESOURCE_OPERATIONS));
        }
        additionalProperties.put(HTTP_RESOURCE_OPERATIONS, String.join(",", httpResourceOperations));

        if (additionalProperties.containsKey(LEGACY_NAMING)) {
            legacyNaming = Boolean.parseBoolean(additionalProperties.get(LEGACY_NAMING).toString());
        }
        additionalProperties.put(LEGACY_NAMING, legacyNaming);

        // Update supporting files

        LOGGER.info("Angular21 Generator initialized with: useInjectFunction={}, " +
                "separateResources={}, readonlyModels={}, httpResourceOperations={}, legacyNaming={}",
                useInjectFunction, separateResources, readonlyModels, httpResourceOperations, legacyNaming);
    }

    @Override
    public void processOpenAPI(OpenAPI openAPI) {
        super.processOpenAPI(openAPI);

        if (openapiGeneratorIgnoreList == null) {
            openapiGeneratorIgnoreList = new HashSet<>();
        }

    }

    @Override
    public String toModelFilename(String name) {
        if (legacyNaming) {
            return super.toModelFilename(name);
        }
        return "./" + toKebabCase(name);
    }

    @Override
    public String toApiFilename(String name) {
        if (legacyNaming) {
            return super.toApiFilename(name);
        }
        return toKebabCase(name) + "-api.service";
    }

    @Override
    public String toModelImport(String name) {
        if (importMapping.containsKey(name)) {
            return importMapping.get(name);
        }
        String filename = toModelFilename(removeModelPrefixSuffix(name));
        while (filename.startsWith("./")) {
            filename = filename.substring(2);
        }
        return "../model/" + filename;
    }

    @Override
    public String toApiName(String name) {
        if (legacyNaming) {
            return super.toApiName(name);
        }
        return StringUtils.camelize(name) + "Api";
    }

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

    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        Map<String, ModelsMap> result = super.postProcessAllModels(objs);

        // Add readonly modifier info to properties
        for (ModelsMap modelsMap : result.values()) {
            for (ModelMap modelMap : modelsMap.getModels()) {
                CodegenModel model = modelMap.getModel();

                // Mark whether this is a Create/Update DTO (should not have readonly)
                boolean isInputDto = model.name.endsWith("Create") ||
                        model.name.endsWith("Update") ||
                        model.name.endsWith("Request") ||
                        model.name.endsWith("Input");

                model.vendorExtensions.put("x-is-input-dto", isInputDto);
                model.vendorExtensions.put("x-use-readonly", readonlyModels && !isInputDto);

                // Process properties
                for (CodegenProperty property : model.vars) {
                    property.vendorExtensions.put("x-is-readonly", readonlyModels && !isInputDto);
                }
            }
        }

        return result;
    }

    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        OperationMap operationsBefore = objs.getOperations();
        Map<String, String> originalPaths = new HashMap<>();
        for (CodegenOperation op : operationsBefore.getOperation()) {
            originalPaths.put(op.operationId, op.path);
        }

        OperationsMap result = super.postProcessOperationsWithModels(objs, allModels);

        OperationMap operations = result.getOperations();
        List<CodegenOperation> ops = operations.getOperation();

        List<CodegenOperation> resourceOperations = new ArrayList<>();

        for (CodegenOperation op : ops) {
            // Add custom vendor extensions
            op.vendorExtensions.put("x-use-inject", useInjectFunction);
            boolean isGet = "GET".equalsIgnoreCase(op.httpMethod);
            boolean useResource = isGet && shouldGenerateHttpResource(op);

            if (isGet) {
                op.vendorExtensions.put("x-is-get", true);
            } else {
                op.vendorExtensions.put("x-is-get", false);
                op.vendorExtensions.put("x-is-mutation", true);
            }
            op.vendorExtensions.put("x-use-http-resource", useResource);
            op.vendorExtensions.put("x-is-resource-operation", useResource);
            op.vendorExtensions.put("x-is-observable-operation", true);
            if (useResource) {
                resourceOperations.add(op);
            }

            // Process path parameters
            processPathParameters(op);

            // Process query parameters
            processQueryParameters(op);

            String originalPath = originalPaths.getOrDefault(op.operationId, op.path);
            String pathTemplate = buildPathTemplate(op, originalPath, false);
            String resourcePathTemplate = buildPathTemplate(op, originalPath, true);
            op.vendorExtensions.put("xPathTemplate", pathTemplate);
            op.vendorExtensions.put("xResourcePathTemplate", resourcePathTemplate);
            if (pathTemplate != null && !pathTemplate.isBlank()) {
                op.path = pathTemplate;
            }
        }

        operations.put("resourceOperations", resourceOperations);
        operations.put("hasResourceOperations", !resourceOperations.isEmpty());
        result.put("hasResourceOperations", !resourceOperations.isEmpty());

        return result;
    }

    private boolean shouldGenerateHttpResource(CodegenOperation operation) {
        Object extensionValue = operation.vendorExtensions.get(HTTP_RESOURCE_VENDOR_EXTENSION);
        if (extensionValue != null) {
            return Boolean.parseBoolean(extensionValue.toString());
        }
        return httpResourceOperations.contains(operation.operationId) || httpResourceOperations.contains(operation.nickname);
    }

    private Set<String> parseOperationIdSet(Object value) {
        if (value == null) {
            return new HashSet<>();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(Object::toString).map(String::trim).filter(entry -> !entry.isEmpty()).collect(Collectors.toCollection(HashSet::new));
        }
        return Arrays.stream(value.toString().split(",")).map(String::trim).filter(entry -> !entry.isEmpty()).collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * Process path parameters for the operation.
     */
    private void processPathParameters(CodegenOperation op) {
        if (op.pathParams != null) {
            for (CodegenParameter param : op.pathParams) {
                // Convert to camelCase for TypeScript
                param.vendorExtensions.put("x-ts-name", toCamelCase(param.paramName));
                param.vendorExtensions.put("x-is-numeric", isNumericParam(param));
            }
        }
    }

    /**
     * Process query parameters for the operation.
     */
    private void processQueryParameters(CodegenOperation op) {
        if (op.queryParams != null && !op.queryParams.isEmpty()) {
            op.vendorExtensions.put("x-has-query-params", true);

            // Generate interface name for query params
            String paramsInterfaceName = toPascalCase(op.operationId) + "Params";
            op.vendorExtensions.put("x-params-interface-name", paramsInterfaceName);

            boolean allOptional = true;
            for (CodegenParameter param : op.queryParams) {
                param.vendorExtensions.put("x-ts-name", toCamelCase(param.paramName));
                if (param.isArray) {
                    param.vendorExtensions.put("x-query-array-exploded", isExplodedQueryArray(param));
                    param.vendorExtensions.put("x-query-array-delimiter", queryArrayDelimiter(param));
                }
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
     * Build a URL path template that encodes path params without using Configuration.
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

    private boolean isNumericParam(CodegenParameter param) {
        if (Boolean.TRUE.equals(param.isInteger) || Boolean.TRUE.equals(param.isNumber)) {
            return true;
        }
        if ("number".equals(param.dataType) || "number".equals(param.baseType) || "integer".equals(param.baseType)) {
            return true;
        }
        return false;
    }

    private boolean isExplodedQueryArray(CodegenParameter param) {
        return param.isExplode || param.isCollectionFormatMulti;
    }

    private String queryArrayDelimiter(CodegenParameter param) {
        if (param.isPipeDelimited || "pipes".equals(param.collectionFormat)) {
            return "|";
        }
        if (param.isSpaceDelimited || "ssv".equals(param.collectionFormat)) {
            return " ";
        }
        if ("tsv".equals(param.collectionFormat)) {
            return "\\t";
        }
        return ",";
    }

    /**
     * Convert string to kebab-case.
     */
    private String toKebabCase(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1-$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
                .toLowerCase();
    }

    /**
     * Convert string to camelCase.
     */
    private String toCamelCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        // Handle snake_case and kebab-case
        Pattern pattern = Pattern.compile("[-_]([a-zA-Z0-9])");
        Matcher matcher = pattern.matcher(name);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, matcher.group(1).toUpperCase());
        }
        matcher.appendTail(buffer);
        String result = buffer.toString();
        // Ensure first character is lowercase
        return Character.toLowerCase(result.charAt(0)) + result.substring(1);
    }

    /**
     * Convert string to PascalCase.
     */
    private String toPascalCase(String name) {
        String camel = toCamelCase(name);
        if (camel == null || camel.isEmpty()) {
            return camel;
        }
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    @Override
    public CodegenType getTag() {
        return CodegenType.CLIENT;
    }

    @Override
    public String apiFileFolder() {
        return outputFolder + File.separator + "api";
    }

    @Override
    public String modelFileFolder() {
        return outputFolder + File.separator + "model";
    }
}
