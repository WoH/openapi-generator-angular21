package de.tum.cit.aet.openapi;

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
}
