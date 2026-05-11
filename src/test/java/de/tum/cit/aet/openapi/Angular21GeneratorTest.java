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

class Angular21GeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesUnifiedTutorialGroupsServicesWithConfigurableResources() throws IOException {
        generateFixture("fixtures/tutorial-groups-openapi.yaml", tempDir);

        Path tutorialGroupApi = tempDir.resolve("api/tutorialGroupApi.service.ts");
        Path freePeriodApi = tempDir.resolve("api/tutorialGroupFreePeriodApi.service.ts");

        assertTrue(Files.exists(tutorialGroupApi));
        assertTrue(Files.exists(freePeriodApi));
        assertFalse(Files.exists(tempDir.resolve("api/tutorialGroupResources.service.ts")));
        assertFalse(Files.exists(tempDir.resolve("api/tutorialGroupFreePeriodResources.service.ts")));
        assertFalse(Files.exists(tempDir.resolve("configuration.ts")));

        String api = Files.readString(tutorialGroupApi);
        assertFalse(api.contains("from '../configuration'"));
        assertFalse(api.contains("inject(Configuration"));
        assertContains(api, "import { HttpClient, HttpEvent, HttpResponse, httpResource, HttpResourceRef } from '@angular/common/http';");
        assertContains(api, "import { inject, Injectable, Signal } from '@angular/core';");
        assertContains(api, "import { CreateOrUpdateTutorialGroupRequest } from '../model/createOrUpdateTutorialGroupRequest';");
        assertContains(api, "import { TutorialGroupDetailData } from '../model/tutorialGroupDetailData';");
        assertFalse(api.contains("from '../model/tutorialGroup'"));
        assertContains(api, "export class TutorialGroupApiService");
        assertContains(api, "export interface GetTutorialGroupsParams");
        assertContains(api, "getTutorialGroupsResource(courseId: Signal<number> | number, params?: Signal<GetTutorialGroupsParams>): HttpResourceRef<Array<TutorialGroupDetailData> | undefined>");
        assertContains(api, "getTutorialGroups(courseId: number, registered?: boolean, campus?: Array<string>, observe?: 'body', reportProgress?: boolean): Observable<Array<TutorialGroupDetailData>>;");
        assertContains(api, "searchParams.append('campus', String(value))");
        assertContains(api, "const BASE_PATH = '/api';");
        assertContains(api, "const url = `${BASE_PATH}/tutorialgroup/courses/${courseId}/tutorial-groups${queryString ? `?${queryString}` : ''}`;");
        assertContains(api, "return `${BASE_PATH}/tutorialgroup/courses/${courseIdValue}/tutorial-groups${query ? `?${query}` : ''}`;");
        assertContains(api, "getTutorialGroup(courseId: number, tutorialGroupId: number, observe?: 'response', reportProgress?: boolean): Observable<HttpResponse<TutorialGroupDetailData>>;");
        assertContains(api, "return this.http.get<TutorialGroupDetailData>(url, { observe, reportProgress });");
        assertContains(api, "createTutorialGroup(courseId: number, createOrUpdateTutorialGroupRequest: CreateOrUpdateTutorialGroupRequest, observe?: 'body', reportProgress?: boolean): Observable<TutorialGroupDetailData>;");
        assertContains(api, "return this.http.post<TutorialGroupDetailData>(url, createOrUpdateTutorialGroupRequest, { observe, reportProgress });");
        assertContains(api, "return this.http.put<TutorialGroupDetailData>(url, createOrUpdateTutorialGroupRequest, { observe, reportProgress });");
        assertContains(api, "return this.http.delete<void>(url");
        assertFalse(api.contains("this.http.POST"));
        assertFalse(api.contains("this.http.PUT"));

        assertContains(api, "exportTutorialGroupsToCSV(courseId: number, fields: Array<string>, observe?: 'body', reportProgress?: boolean): Observable<Blob>;");
        assertContains(api, "exportTutorialGroupsToCSV(courseId: number, fields: Array<string>, observe?: 'response', reportProgress?: boolean): Observable<HttpResponse<Blob>>;");
        assertContains(api, "exportTutorialGroupsToCSV(courseId: number, fields: Array<string>, observe?: 'events', reportProgress?: boolean): Observable<HttpEvent<Blob>>;");
        assertContains(api, "return this.http.get(url, { observe, reportProgress, responseType: 'blob' as 'blob' });");
        assertFalse(api.contains("return this.http.get<TutorialGroupDetailData>(url, { observe, reportProgress, responseType"));

        assertContains(api, "getTutorialGroupAvatarResource(courseId: Signal<number> | number, tutorialGroupId: Signal<number> | number): HttpResourceRef<Blob | undefined>");
        assertContains(api, "return httpResource.blob(() => {");
        assertFalse(api.contains("httpResource<Blob>"));

        assertContains(api, "export interface SearchTutorialGroupsParams {");
        assertContains(api, "searchTutorialGroupsResource(courseId: Signal<number> | number, params: Signal<SearchTutorialGroupsParams>): HttpResourceRef<Array<TutorialGroupDetailData> | undefined>");
        assertFalse(api.contains("searchTutorialGroupsResource(courseId: Signal<number> | number, params?: Signal<SearchTutorialGroupsParams>)"));

        String freePeriodApiContent = Files.readString(freePeriodApi);
        assertContains(freePeriodApiContent, "getTutorialGroupFreePeriod(courseId: number, configurationId: number, freePeriodId: number, observe?: 'body', reportProgress?: boolean): Observable<TutorialGroupFreePeriod>;");
        assertFalse(freePeriodApiContent.contains("httpResource"));

        String model = Files.readString(tempDir.resolve("model/tutorialGroupDetailData.ts"));
        assertContains(model, "readonly id: number;");
        assertContains(model, "readonly title: string;");

        String configurationModel = Files.readString(tempDir.resolve("model/tutorialGroupConfiguration.ts"));
        assertContains(configurationModel, "import type { TutorialGroupFreePeriod } from './tutorialGroupFreePeriod';");
        assertFalse(configurationModel.contains("from 'tutorial-group-free-period'"));

        String requestModel = Files.readString(tempDir.resolve("model/createOrUpdateTutorialGroupRequest.ts"));
        assertContains(requestModel, "title: string;");
        assertFalse(requestModel.contains("readonly title"));
    }

    @Test
    void generatesKebabCaseFilenamesWhenLegacyNamingDisabled() throws IOException {
        generateFixture("fixtures/tutorial-groups-openapi.yaml", tempDir, Map.of("legacyNaming", false));

        Path tutorialGroupApi = tempDir.resolve("api/tutorial-group-api.service.ts");
        Path detailDataModel = tempDir.resolve("model/tutorial-group-detail-data.ts");
        Path configurationModel = tempDir.resolve("model/tutorial-group-configuration.ts");

        assertTrue(Files.exists(tutorialGroupApi), "expected kebab-case api file");
        assertTrue(Files.exists(detailDataModel), "expected kebab-case model file");
        assertFalse(Files.exists(tempDir.resolve("api/tutorialGroupApi.service.ts")));
        assertFalse(Files.exists(tempDir.resolve("model/tutorialGroupDetailData.ts")));

        String api = Files.readString(tutorialGroupApi);
        assertContains(api, "import { TutorialGroupDetailData } from '../model/tutorial-group-detail-data';");
        assertContains(api, "import { CreateOrUpdateTutorialGroupRequest } from '../model/create-or-update-tutorial-group-request';");
        assertFalse(api.contains("from '../model/tutorialGroupDetailData'"));

        String configuration = Files.readString(configurationModel);
        assertContains(configuration, "import type { TutorialGroupFreePeriod } from './tutorial-group-free-period';");
        assertFalse(configuration.contains("from './tutorialGroupFreePeriod'"));
    }

    @Test
    void respectsExplodeFalseForArrayQueryParameters() throws IOException {
        generateFixture("fixtures/query-serialization-openapi.yaml", tempDir);

        String api = Files.readString(tempDir.resolve("api/queryApi.service.ts"));
        assertContains(api, "queryParams.set('ids', ids.map(item => String(item)).join(','));");
        assertContains(api, "tags.forEach(item => queryParams.append('tags', String(item)));");
        assertContains(api, "searchParams.set('ids', queryParams.ids.map(value => String(value)).join(','));");
        assertContains(api, "queryParams.tags.forEach(value => searchParams.append('tags', String(value)));");
        assertFalse(api.contains("ids.forEach(item => queryParams.append('ids'"));
        assertFalse(api.contains("queryParams.ids.forEach(value => searchParams.append('ids'"));
    }

    private static void generateFixture(String fixture, Path outputDir) {
        generateFixture(fixture, outputDir, Map.of());
    }

    private static void generateFixture(String fixture, Path outputDir, Map<String, Object> additionalProperties) {
        String inputSpec = Path.of("src/test/resources").resolve(fixture).toAbsolutePath().toString();
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(Angular21Generator.GENERATOR_NAME)
                .setInputSpec(inputSpec)
                .setOutputDir(outputDir.toString());
        additionalProperties.forEach(configurator::addAdditionalProperty);

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    private static void assertContains(String actual, String expected) {
        assertTrue(actual.contains(expected), () -> "Expected generated output to contain:\n" + expected + "\n\nActual output:\n" + actual);
    }
}
