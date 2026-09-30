/*
 * Copyright (c) 2024 TUM Applied Education Technologies (AET)
 * Licensed under the MIT License
 */
package de.tum.cit.aet.openapi;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the TypeScript snippets that the templates print for an operation or a parameter, such as the
 * {@code HttpClient} call, the httpResource signature, the URL template literal and the {@code FormData} appends, and
 * stores them in vendor extensions. The snippets depend only on the codegen model, not on the generator's state.
 */
final class TypeScriptSnippets {

    /** TypeScript types that {@code String()} turns into a form field value without losing information. */
    private static final Set<String> TS_SCALAR_TYPES = Set.of("string", "number", "boolean");
    /** An ASCII identifier, which TypeScript accepts as a property name without quotes. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /**
     * How {@code HttpClient} and {@code httpResource} must read a response body. Without an explicit
     * {@code responseType} the client parses JSON and throws on text or binary payloads (iCalendar files, CSV
     * exports, plain-text tokens, generated source code).
     */
    enum ResponseKind {
        /** Parsed as JSON into the return type, including JSON-string endpoints. */
        JSON,
        /** A string return whose produced media types are all {@code text/*}. */
        TEXT,
        /** A binary ({@code Blob}) return. */
        BLOB,
        /** A file download; the service method returns the {@code HttpResponse} so callers get its headers. */
        FILE;

        static ResponseKind of(CodegenOperation op) {
            if (op.isResponseFile) {
                return FILE;
            }
            if ("Blob".equals(op.returnType)) {
                return BLOB;
            }
            if ("string".equals(op.returnType) && producesTextOnly(op)) {
                return TEXT;
            }
            return JSON;
        }
    }

    /**
     * Processes path parameters for a single operation: sets {@code x-is-numeric} on each parameter, since numeric
     * parameters don't need URI encoding.
     *
     * @param op the operation whose path parameters should be processed
     */
    static void processPathParameters(CodegenOperation op) {
        for (CodegenParameter param : op.pathParams) {
            param.vendorExtensions.put("x-is-numeric", isNumericParam(param));
        }
    }

    static String paramsInterfaceName(CodegenOperation op) {
        return Names.toPascalCase(op.operationId) + "Params";
    }

    static boolean allQueryParamsOptional(CodegenOperation op) {
        return op.queryParams.stream().noneMatch(param -> param.required);
    }

    /**
     * Builds the {@code HttpClient} call of a service method once, so the template prints a single
     * {@code return this.http.<method><typeArg>(<args>);} line.
     *
     * <p>Sets vendor extensions on the operation:</p>
     * <ul>
     *   <li>{@code x-observable-type} &mdash; what the service method's {@code Observable} emits: the return type, or
     *       the {@code HttpResponse<Blob>} of a file download</li>
     *   <li>{@code x-http-type-arg} &mdash; the {@code <T>} type argument, empty when a {@code responseType}
     *       option selects a non-JSON overload that already fixes the result type</li>
     *   <li>{@code x-http-args} &mdash; the argument list: {@code url}, the payload for methods that take one,
     *       and an options object with, in this order and only when present, {@code body} (DELETE only),
     *       {@code headers}, {@code responseType} and {@code observe}</li>
     * </ul>
     *
     * @param op       the operation whose HttpClient call should be built
     * @param response how the response body is read
     */
    static void buildHttpCall(CodegenOperation op, ResponseKind response) {
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
        options.addAll(switch (response) {
            case JSON -> List.of();
            case TEXT -> List.of("responseType: 'text'");
            case BLOB -> List.of("responseType: 'blob'");
            case FILE -> List.of("responseType: 'blob'", "observe: 'response'");
        });
        if (!options.isEmpty()) {
            args.add("{ " + String.join(", ", options) + " }");
        }

        String returnType = op.returnType != null ? op.returnType : "void";
        op.vendorExtensions.put("x-observable-type", response == ResponseKind.FILE ? "HttpResponse<Blob>" : returnType);
        op.vendorExtensions.put("x-http-type-arg", response == ResponseKind.JSON ? "<" + returnType + ">" : "");
        op.vendorExtensions.put("x-http-args", String.join(", ", args));
    }

    /**
     * Builds the parameter list, the httpResource factory and the request expression of a GET operation's
     * httpResource function.
     *
     * <p>Sets vendor extensions on the operation:</p>
     * <ul>
     *   <li>{@code x-resource-params} &mdash; path parameters, then header parameters (each a signal or a plain
     *       value), then the query {@code params} signal. An argument is marked optional ({@code ?}) only when
     *       every argument after it is optional too; an optional argument before a required one accepts
     *       {@code undefined} instead.</li>
     *   <li>{@code x-resource-type} &mdash; the value type of the {@code HttpResourceRef}</li>
     *   <li>{@code x-resource-factory} &mdash; {@code httpResource<T>} for JSON, {@code httpResource.text} or
     *       {@code httpResource.blob} otherwise</li>
     *   <li>{@code x-resource-request} &mdash; what the request function returns: the URL template literal, or
     *       {@code { url: ..., headers }} when the operation declares header parameters</li>
     * </ul>
     *
     * @param op                   the GET operation
     * @param response             how the response body is read
     * @param resourcePathTemplate the URL path with {@code ${...}} placeholders for the resource template
     */
    static void buildResourceFunction(CodegenOperation op, ResponseKind response, String resourcePathTemplate) {
        List<ResourceArg> args = new ArrayList<>();
        for (CodegenParameter param : op.pathParams) {
            args.add(new ResourceArg(param.paramName, signalOrValue(param.dataType), false));
        }
        for (CodegenParameter param : op.headerParams) {
            args.add(new ResourceArg(param.paramName, signalOrValue(param.dataType), !param.required));
        }
        boolean hasQueryParams = !op.queryParams.isEmpty();
        if (hasQueryParams) {
            args.add(new ResourceArg("params", "Signal<" + paramsInterfaceName(op) + ">", allQueryParamsOptional(op)));
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

        String resourceType = op.returnType != null ? op.returnType : "unknown";
        op.vendorExtensions.put("x-resource-type", resourceType);
        op.vendorExtensions.put("x-resource-factory", switch (response) {
            case JSON -> "httpResource<" + resourceType + ">";
            case TEXT -> "httpResource.text";
            case BLOB, FILE -> "httpResource.blob";
        });

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
     * <p>{@code FormData} only accepts strings and Blobs. The collection shape decides first: an array or set of
     * binaries goes out as one part per item, and any other array or set as one {@code application/json} array part.
     * Of the other fields, a binary is appended as it is, a scalar or enum is converted with {@code String()}, and
     * everything else (objects, maps) goes out as one {@code application/json} part, which is what Spring's
     * {@code @RequestPart} expects for a DTO.</p>
     *
     * @param op the operation whose form parameters should be processed
     */
    static void processFormParameters(CodegenOperation op) {
        for (CodegenParameter param : op.formParams) {
            String name = param.paramName;
            String key = stringLiteral(param.baseName);
            String statement;
            if (param.isArray) {
                statement = isBinaryType(param.items != null ? param.items.dataType : null)
                        ? name + ".forEach(item => formData.append(" + key + ", item));"
                        : appendJsonPart(key, "Array.from(" + name + ")");
            } else if (isBinaryType(param.dataType)) {
                statement = "formData.append(" + key + ", " + name + ");";
            } else if (param.isEnum || param.isEnumRef || TS_SCALAR_TYPES.contains(param.dataType)) {
                statement = "formData.append(" + key + ", String(" + name + "));";
            } else {
                statement = appendJsonPart(key, name);
            }
            param.vendorExtensions.put("x-form-append", statement);
        }
    }

    private static String appendJsonPart(String key, String value) {
        return "formData.append(" + key + ", new Blob([JSON.stringify(" + value + ")], { type: 'application/json' }));";
    }

    private static boolean isBinaryType(String dataType) {
        return "Blob".equals(dataType) || "File".equals(dataType);
    }

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
     * @return the TypeScript template literal path (e.g., {@code /api/jobs/${idPath}/pdf})
     */
    static String buildPathTemplate(CodegenOperation op, String originalPath, boolean useSignalValue) {
        String path = originalPath;
        for (CodegenParameter param : op.pathParams) {
            String name = param.paramName;
            boolean isNumeric = isNumericParam(param);

            String valueVar;
            if (useSignalValue) {
                valueVar = isNumeric ? name + "Value" : name + "Path";
            } else {
                valueVar = isNumeric ? name : name + "Path";
            }

            String placeholder = "{" + param.baseName + "}";
            path = path.replace(placeholder, "${" + valueVar + "}");
        }
        return path;
    }

    /**
     * Checks whether a parameter represents a numeric type (integer or number),
     * which determines whether it needs URI encoding in the generated URL template.
     *
     * @param param the codegen parameter to check
     * @return {@code true} if the parameter is numeric, {@code false} otherwise
     */
    private static boolean isNumericParam(CodegenParameter param) {
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
    private static boolean producesTextOnly(CodegenOperation op) {
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
    static String toPropertyKey(String jsonKey) {
        return IDENTIFIER.matcher(jsonKey).matches() ? jsonKey : stringLiteral(jsonKey);
    }

    /**
     * Returns a single-quoted TypeScript string literal for a string from the spec, such as a wire name. Jackson's JSON
     * string encoder escapes backslashes, double quotes and control characters the way a TypeScript string literal
     * reads them; the apostrophe that would end the literal is escaped on top.
     *
     * @param value the string as the spec writes it
     * @return the quoted literal, e.g. {@code 'it\'s'}
     */
    private static String stringLiteral(String value) {
        return "'" + new String(JsonStringEncoder.getInstance().quoteAsString(value)).replace("'", "\\'") + "'";
    }

    private TypeScriptSnippets() {
    }
}
