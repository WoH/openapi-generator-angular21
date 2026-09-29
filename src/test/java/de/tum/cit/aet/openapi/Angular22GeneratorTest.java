package de.tum.cit.aet.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

class Angular22GeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesServicesAndResourcesForTutorialGroups() throws IOException {
        generateFixture("fixtures/tutorial-groups-openapi.yaml", Map.of());

        String api = Files.readString(tempDir.resolve("api/tutorial-group-api.ts"));
        assertContains(api, "export class TutorialGroupApi");
        assertContains(api, "import { TutorialGroupDetailData } from '../model/tutorial-group-detail-data';");
        assertContains(api, "import { CreateOrUpdateTutorialGroupRequest } from '../model/create-or-update-tutorial-group-request';");
        // GETs stay available as Observable methods next to the resources.
        assertContains(api, "getTutorialGroups(courseId: number, registered?: boolean, campus?: Array<string>): Observable<Array<TutorialGroupDetailData>>");
        assertContains(api, "const url = `${this.basePath}/tutorialgroup/courses/${courseId}/tutorial-groups${queryString ? `?${queryString}` : ''}`;");
        assertContains(api, "return this.http.post<TutorialGroupDetailData>(url, createOrUpdateTutorialGroupRequest);");
        assertContains(api, "return this.http.delete<void>(url);");
        assertContains(api, "return this.http.get(url, { responseType: 'blob', observe: 'response' });");
        assertContains(api, "return this.http.get(url, { responseType: 'text' });");

        String resources = Files.readString(tempDir.resolve("api/tutorial-group-resources.ts"));
        assertContains(resources, "export function getTutorialGroupsResource(courseId: Signal<number | undefined> | number, params?: Signal<GetTutorialGroupsParams>): HttpResourceRef<Array<TutorialGroupDetailData> | undefined>");
        assertContains(resources, "return `${BASE_PATH}/tutorialgroup/courses/${courseIdValue}/tutorial-groups${query ? `?${query}` : ''}`;");
        // An id that is not known yet (for example before the route resolved) skips the request instead of calling .../undefined.
        assertContains(resources, "if (courseIdValue === undefined) {\n            return undefined;\n        }");
        assertContains(resources, "export function searchTutorialGroupsResource(courseId: Signal<number | undefined> | number, params: Signal<SearchTutorialGroupsParams>)");
        // Non-JSON GETs must not go through the JSON parser.
        assertContains(resources, "getTutorialGroupAvatarResource(courseId: Signal<number | undefined> | number, tutorialGroupId: Signal<number | undefined> | number): HttpResourceRef<Blob | undefined>");
        assertContains(resources, "return httpResource.blob(() => {");
        assertContains(resources, "return httpResource.text(() => {");
        assertFalse(resources.contains("httpResource<Blob>"));
        assertFalse(resources.contains("httpResource<string>"));

        String configurationModel = Files.readString(tempDir.resolve("model/tutorial-group-configuration.ts"));
        assertContains(configurationModel, "import type { TutorialGroupFreePeriod } from './tutorial-group-free-period';");

        assertContains(Files.readString(tempDir.resolve("model/tutorial-group-detail-data.ts")), "readonly title: string;");
        String requestModel = Files.readString(tempDir.resolve("model/create-or-update-tutorial-group-request.ts"));
        assertContains(requestModel, "title: string;");
        assertFalse(requestModel.contains("readonly title"));
    }

    @Test
    void generatesObservableGetsOnlyWhenHttpResourceIsDisabled() throws IOException {
        generateFixture("fixtures/tutorial-groups-openapi.yaml", Map.of("useHttpResource", "false", "separateResources", "false"));

        assertFalse(Files.exists(tempDir.resolve("api/tutorial-group-resources.ts")));
        String api = Files.readString(tempDir.resolve("api/tutorial-group-api.ts"));
        assertContains(api, "getTutorialGroups(courseId: number, registered?: boolean, campus?: Array<string>): Observable<Array<TutorialGroupDetailData>>");
        assertFalse(api.contains("httpResource"));
    }

    @Test
    void generatesApisWhoseClassNameIsShorterThanTheServiceSuffix() throws IOException {
        // FaqApi has fewer characters than the parent generator's default service suffix "Service".
        generateFixture("fixtures/short-tag-openapi.yaml", Map.of());

        assertContains(Files.readString(tempDir.resolve("api/faq-api.ts")), "export class FaqApi");
    }

    @Test
    void appendsMultipartFieldsAsBlobsStringsOrJsonParts() throws IOException {
        generateFixture("fixtures/multipart-openapi.yaml", Map.of());

        String api = Files.readString(tempDir.resolve("api/upload-api.ts"));
        // Objects, arrays of objects and maps go out as one JSON part each, which Spring's @RequestPart reads.
        assertContains(api, """
                        if (course !== undefined && course !== null) {
                            formData.append('course', new Blob([JSON.stringify(course)], { type: 'application/json' }));
                        }
                """);
        assertContains(api, "formData.append('pages', new Blob([JSON.stringify(pages)], { type: 'application/json' }));");
        assertContains(api, "formData.append('labels', new Blob([JSON.stringify(labels)], { type: 'application/json' }));");
        // Binary fields are appended as they are.
        assertContains(api, "formData.append('file', file);");
        assertContains(api, "files.forEach(item => formData.append('files', item));");
        // Scalars and enums become strings, the only non-Blob value FormData accepts.
        assertContains(api, "formData.append('name', String(name));");
        assertContains(api, "formData.append('count', String(count));");
        assertContains(api, "formData.append('ratio', String(ratio));");
        assertContains(api, "formData.append('active', String(active));");
        assertContains(api, "formData.append('mode', String(mode));");
        assertContains(api, "return this.http.post<CourseCreate>(url, formData);");
    }

    @Test
    void passesDeleteRequestBodiesInTheOptionsObject() throws IOException {
        generateFixture("fixtures/delete-body-openapi.yaml", Map.of());

        String api = Files.readString(tempDir.resolve("api/user-api.ts"));
        // HttpClient.delete takes (url, options); a body passed as the second argument selects the wrong overload.
        assertContains(api, "return this.http.delete<void>(url, { body: bulkUserDeletionRequest });");
        assertContains(api, "return this.http.delete<DeletionSummary>(url, { body: permanentUserDeletionRequest });");
    }

    @Test
    void readsTextResponsesOfNonGetOperationsAsText() throws IOException {
        generateFixture("fixtures/text-response-openapi.yaml", Map.of());

        String api = Files.readString(tempDir.resolve("api/programming-exercise-api.ts"));
        // A text/plain body is not JSON; without responseType: 'text' HttpClient fails to parse it.
        assertContains(api, "return this.http.put(url, generateTestsRequest, { responseType: 'text' });");
        assertContains(api, "return this.http.post(url, null, { responseType: 'text' });");
        assertContains(api, "return this.http.delete(url, { body: revokeTokenRequest, responseType: 'text' });");
        // A JSON string keeps the JSON parser.
        assertContains(api, """
                    renameExercise(exerciseId: number): Observable<string> {
                        const url = `${this.basePath}/api/programming-exercises/${exerciseId}/name`;
                        return this.http.post<string>(url, null);
                """);
    }

    @Test
    void sendsDeclaredHeaderParameters() throws IOException {
        generateFixture("fixtures/header-params-openapi.yaml", Map.of());

        String api = Files.readString(tempDir.resolve("api/repository-api.ts"));
        assertContains(api, """
                        const url = `${this.basePath}/api/exercises/${exerciseId}/repository${queryString ? `?${queryString}` : ''}`;
                        const headers: Record<string, string> = {};
                        if (authorization !== undefined && authorization !== null) {
                            headers['Authorization'] = String(authorization);
                        }
                        if (xTraceId !== undefined && xTraceId !== null) {
                            headers['X-Trace-Id'] = String(xTraceId);
                        }
                        return this.http.get<RepositoryFiles>(url, { headers });
                """);
        assertContains(api, "return this.http.post<void>(url, repositoryFeedback, { headers });");
        assertContains(api, "return this.http.get(url, { headers, responseType: 'text' });");
        assertContains(api, "return this.http.get<RepositoryFiles>(url);");

        String resources = Files.readString(tempDir.resolve("api/repository-resources.ts"));
        // Header values are function arguments like path parameters; an optional one stays optional only while
        // every later argument is optional too.
        assertContains(resources, "export function getRepositoryResource(exerciseId: Signal<number | undefined> | number, authorization: Signal<string | undefined> | string, xTraceId?: Signal<string | undefined> | string, params?: Signal<GetRepositoryParams>): HttpResourceRef<RepositoryFiles | undefined>");
        assertContains(resources, """
                        const headers: Record<string, string> = {};
                        const authorizationValue = typeof authorization === 'function' ? authorization() : authorization;
                        if (authorizationValue !== undefined && authorizationValue !== null) {
                            headers['Authorization'] = String(authorizationValue);
                        }
                        const xTraceIdValue = typeof xTraceId === 'function' ? xTraceId() : xTraceId;
                        if (xTraceIdValue !== undefined && xTraceIdValue !== null) {
                            headers['X-Trace-Id'] = String(xTraceIdValue);
                        }
                """);
        // With headers the resource returns the request object form, without them the plain URL string.
        assertContains(resources, "return { url: `${BASE_PATH}/api/exercises/${exerciseIdValue}/repository${query ? `?${query}` : ''}`, headers };");
        assertContains(resources, "return { url: `${BASE_PATH}/api/exercises/${exerciseIdValue}/repository/token`, headers };");
        assertContains(resources, "return `${BASE_PATH}/api/exercises/${exerciseIdValue}`;");
    }

    @Test
    void renamesParametersThatCollideWithTemplateLocals() throws IOException {
        generateFixture("fixtures/local-name-collision-openapi.yaml", Map.of());

        String api = Files.readString(tempDir.resolve("api/link-api.ts"));
        // The TypeScript name changes, the wire name stays.
        assertContains(api, """
                    getLinkPreview(urlParam: string): Observable<LinkPreview> {
                        const queryParams = new URLSearchParams();
                        if (urlParam !== undefined && urlParam !== null) {
                            queryParams.set('url', String(urlParam));
                        }
                        const queryString = queryParams.toString();
                        const url = `${this.basePath}/api/link-preview${queryString ? `?${queryString}` : ''}`;
                """);
        assertContains(api, "search(queryParam: string, paramsParam: number, headersParam?: string, queryStringParam?: string, queryParamsParam?: string): Observable<Array<LinkPreview>>");
        assertContains(api, "const queryParamPath = encodeURIComponent(String(queryParam));");
        assertContains(api, "queryParams.set('queryString', String(queryStringParam));");
        assertContains(api, "queryParams.set('queryParams', String(queryParamsParam));");
        assertContains(api, "const url = `${this.basePath}/api/search/${queryParamPath}/${paramsParam}${queryString ? `?${queryString}` : ''}`;");
        assertContains(api, "headers['headers'] = String(headersParam);");
        assertContains(api, "upload(formDataParam?: string): Observable<void>");
        assertContains(api, "formData.append('formData', String(formDataParam));");

        String resources = Files.readString(tempDir.resolve("api/link-resources.ts"));
        // Query parameters are properties of the params object, which never collide with a local.
        assertContains(resources, "searchParams.set('url', String(queryParams.url));");
        assertContains(resources, "export function searchResource(queryParam: Signal<string | undefined> | string, paramsParam: Signal<number | undefined> | number, headersParam?: Signal<string | undefined> | string, params?: Signal<SearchParams>)");
        assertContains(resources, "const queryParamValue = typeof queryParam === 'function' ? queryParam() : queryParam;");
        assertContains(resources, "const headersParamValue = typeof headersParam === 'function' ? headersParam() : headersParam;");
        assertContains(resources, "return { url: `${BASE_PATH}/api/search/${queryParamPath}/${paramsParamValue}${query ? `?${query}` : ''}`, headers };");
    }

    @Test
    void escapesReservedWordsInEveryParameterIdentifier() throws IOException {
        generateFixture("fixtures/reserved-parameter-openapi.yaml", Map.of());

        // Reserved words cannot be parameter names or variables, so the escaped name is used everywhere the parameter
        // is an identifier.
        String api = Files.readString(tempDir.resolve("api/package-api.ts"));
        assertContains(api, "getPackage(_default: number, _package: string, _function?: string, _new?: boolean): Observable<string>");
        assertContains(api, "const _packagePath = encodeURIComponent(String(_package));");
        assertContains(api, "const url = `${this.basePath}/api/packages/${_default}/${_packagePath}${queryString ? `?${queryString}` : ''}`;");

        String resources = Files.readString(tempDir.resolve("api/package-resources.ts"));
        assertContains(resources, "export function getPackageResource(_default: Signal<number | undefined> | number, _package: Signal<string | undefined> | string, _function?: Signal<string | undefined> | string, params?: Signal<GetPackageParams>)");
        assertContains(resources, "return { url: `${BASE_PATH}/api/packages/${_defaultValue}/${_packagePath}${query ? `?${query}` : ''}`, headers };");
        // A query parameter is a property of the params object, where a reserved word is a valid name.
        assertContains(resources, "new?: boolean;");
    }

    @Test
    void namesModelPropertiesExactlyLikeTheirJsonKeys() throws IOException {
        generateFixture("fixtures/reserved-property-openapi.yaml", Map.of());

        // Interfaces describe the JSON as it is, so a property name must be its JSON key. Reserved words are valid
        // property names; keys that are not identifiers are quoted.
        assertContains(Files.readString(tempDir.resolve("model/result-summary.ts")), """
                export interface ResultSummary {
                    readonly final?: boolean;
                    readonly delete: boolean;
                    readonly class?: string;
                    readonly 'x-y'?: string;
                    readonly first_name?: string;
                    readonly score?: number;
                }
                """);
        // Parameters are identifiers, so reserved words stay escaped there.
        assertContains(Files.readString(tempDir.resolve("api/result-api.ts")), "getResults(_final?: boolean): Observable<Array<ResultSummary>>");
    }

    @Test
    void writesInlineModelsToTheFileTheirImportsName() throws IOException {
        generateFixture("fixtures/inline-model-openapi.yaml", Map.of());

        // The inline response schema is named getExam_200_response; its class is GetExam200Response.
        assertContains(Files.readString(tempDir.resolve("api/exam-api.ts")), "import { GetExam200Response } from '../model/get-exam200response';");
        assertContains(Files.readString(tempDir.resolve("api/exam-resources.ts")), "import { GetExam200Response } from '../model/get-exam200response';");
        assertContains(Files.readString(tempDir.resolve("model/get-exam200response.ts")), "export interface GetExam200Response {");
        assertFalse(Files.exists(tempDir.resolve("model/get-exam-200-response.ts")));
    }

    @Test
    void leavesPropertiesOfTheParentInterfaceToExtends() throws IOException {
        generateFixture("fixtures/subtype-openapi.yaml", Map.of());

        assertContains(Files.readString(tempDir.resolve("model/exercise.ts")), """
                export interface Exercise {
                    readonly type: string;
                }
                """);
        // Redeclaring the required discriminator as optional would not compile (TS2430); extends already brings it.
        assertContains(Files.readString(tempDir.resolve("model/text-exercise.ts")), """
                export interface TextExercise extends Exercise {
                    readonly name?: string;
                }
                """);
    }

    @Test
    void keepsPropertiesThatASubtypeNarrows() throws IOException {
        generateFixture("fixtures/subtype-narrowing-openapi.yaml", Map.of());

        // A required QuizExercise is assignable to the parent's optional Exercise, so the subtype may declare it.
        assertContains(Files.readString(tempDir.resolve("model/quiz-participation.ts")), """
                export interface QuizParticipation extends Participation {
                    readonly exercise: QuizExercise;
                    readonly submitted?: boolean;
                }
                """);
    }

    @Test
    void expandsObjectQueryParametersIntoOneKeyPerProperty() throws IOException {
        generateFixture("fixtures/object-query-openapi.yaml", Map.of());

        // String(search) sends search=[object Object]. OpenAPI's default form/explode style sends one key per
        // property, which is what Spring binds to a DTO.
        String api = Files.readString(tempDir.resolve("api/score-api.ts"));
        assertContains(api, """
                        if (search !== undefined && search !== null) {
                            appendQueryObject(queryParams, search);
                        }
                """);
        assertContains(api, "queryParams.set('includeTeams', String(includeTeams));");
        assertContains(api, """
                /**
                 * Appends an object query parameter as OpenAPI form/explode and Spring's data binding expect it: one key per
                 * property, arrays as repeated keys, nested objects as dotted keys.
                 */
                function appendQueryObject(target: URLSearchParams, value: object, prefix?: string): void {
                    for (const [key, entry] of Object.entries(value)) {
                        const name = prefix ? `${prefix}.${key}` : key;
                        if (entry === undefined || entry === null) {
                            continue;
                        }
                        if (Array.isArray(entry)) {
                            entry.forEach((item) => target.append(name, String(item)));
                        } else if (typeof entry === 'object') {
                            appendQueryObject(target, entry, name);
                        } else {
                            target.append(name, String(entry));
                        }
                    }
                }
                """);
        assertEquals(1, countOccurrences(api, "function appendQueryObject("));

        String resources = Files.readString(tempDir.resolve("api/score-resources.ts"));
        assertContains(resources, """
                        if (queryParams.search !== undefined && queryParams.search !== null) {
                            appendQueryObject(searchParams, queryParams.search);
                        }
                """);
        assertEquals(1, countOccurrences(resources, "function appendQueryObject("));

        // An unused helper would fail noUnusedLocals, so a tag without object query parameters does not declare it.
        assertFalse(Files.readString(tempDir.resolve("api/course-api.ts")).contains("appendQueryObject"));
        assertFalse(Files.readString(tempDir.resolve("api/course-resources.ts")).contains("appendQueryObject"));
    }

    @Test
    void declaresTheQueryObjectHelperOnceNextToInlineResources() throws IOException {
        generateFixture("fixtures/object-query-openapi.yaml", Map.of("separateResources", "false"));

        String api = Files.readString(tempDir.resolve("api/score-api.ts"));
        assertContains(api, "appendQueryObject(queryParams, search);");
        assertContains(api, "appendQueryObject(searchParams, queryParams.search);");
        assertEquals(1, countOccurrences(api, "function appendQueryObject("));
        assertFalse(Files.readString(tempDir.resolve("api/course-api.ts")).contains("appendQueryObject"));
    }

    private void generateFixture(String fixture, Map<String, Object> additionalProperties) {
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(Angular22Generator.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources").resolve(fixture).toAbsolutePath().toString())
                .setOutputDir(tempDir.toString());
        additionalProperties.forEach(configurator::addAdditionalProperty);
        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    private static void assertContains(String actual, String expected) {
        assertTrue(actual.contains(expected), () -> "Expected generated output to contain:\n" + expected + "\n\nActual output:\n" + actual);
    }

    /**
     * Counts the non-overlapping occurrences of {@code needle} in {@code text}.
     */
    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }
}
