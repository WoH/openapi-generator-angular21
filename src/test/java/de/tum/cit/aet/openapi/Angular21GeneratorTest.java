package de.tum.cit.aet.openapi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

class Angular21GeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesTutorialGroupsResourcesAndMutationServices() throws IOException {
        generateFixture("fixtures/tutorial-groups-openapi.yaml", tempDir);

        Path tutorialGroupResources = tempDir.resolve("api/tutorial-group-resources.ts");
        Path tutorialGroupApi = tempDir.resolve("api/tutorial-group-api.ts");
        Path freePeriodResources = tempDir.resolve("api/tutorial-group-free-period-resources.ts");
        Path freePeriodApi = tempDir.resolve("api/tutorial-group-free-period-api.ts");

        assertTrue(Files.exists(tutorialGroupResources));
        assertTrue(Files.exists(tutorialGroupApi));
        assertTrue(Files.exists(freePeriodResources));
        assertTrue(Files.exists(freePeriodApi));

        String resources = Files.readString(tutorialGroupResources);
        assertContains(resources, "import { TutorialGroupDetailData } from '../models/tutorial-group-detail-data';");
        assertFalse(resources.contains("from '../models/tutorial-group'"));
        assertFalse(resources.contains("CreateOrUpdateTutorialGroupRequest"));
        assertContains(resources, "export function getTutorialGroupsResource(");
        assertContains(resources, "courseId: Signal<number> | number");
        assertContains(resources, "params?: Signal<GetTutorialGroupsParams>");
        assertContains(resources, "searchParams.append('campus', String(value))");
        assertContains(resources, "return `${BASE_PATH}/tutorialgroup/courses/${courseIdValue}/tutorial-groups${query ? `?${query}` : ''}`;");
        assertFalse(resources.contains("createTutorialGroup"));

        String api = Files.readString(tutorialGroupApi);
        assertContains(api, "import { CreateOrUpdateTutorialGroupRequest } from '../models/create-or-update-tutorial-group-request';");
        assertContains(api, "import { TutorialGroupDetailData } from '../models/tutorial-group-detail-data';");
        assertFalse(api.contains("from '../models/tutorial-group'"));
        assertContains(api, "export class TutorialGroupApi");
        assertContains(api, "createTutorialGroup(courseId: number, createOrUpdateTutorialGroupRequest: CreateOrUpdateTutorialGroupRequest): Observable<TutorialGroupDetailData>");
        assertContains(api, "return this.http.post<TutorialGroupDetailData>(url, createOrUpdateTutorialGroupRequest);");
        assertContains(api, "return this.http.put<TutorialGroupDetailData>(url, createOrUpdateTutorialGroupRequest);");
        assertContains(api, "return this.http.delete<void>(url");
        assertFalse(api.contains("this.http.POST"));
        assertFalse(api.contains("this.http.PUT"));
        assertFalse(api.contains("getTutorialGroup("));

        String model = Files.readString(tempDir.resolve("models/tutorial-group-detail-data.ts"));
        assertContains(model, "readonly id: number;");
        assertContains(model, "readonly title: string;");

        String configurationModel = Files.readString(tempDir.resolve("models/tutorial-group-configuration.ts"));
        assertContains(configurationModel, "import type { TutorialGroupFreePeriod } from './tutorial-group-free-period';");
        assertFalse(configurationModel.contains("from 'tutorial-group-free-period'"));

        String requestModel = Files.readString(tempDir.resolve("models/create-or-update-tutorial-group-request.ts"));
        assertContains(requestModel, "title: string;");
        assertFalse(requestModel.contains("readonly title"));
    }

    private static void generateFixture(String fixture, Path outputDir) {
        String inputSpec = Path.of("src/test/resources").resolve(fixture).toAbsolutePath().toString();
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(Angular21Generator.GENERATOR_NAME)
                .setInputSpec(inputSpec)
                .setOutputDir(outputDir.toString());

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    private static void assertContains(String actual, String expected) {
        assertTrue(actual.contains(expected), () -> "Expected generated output to contain:\n" + expected + "\n\nActual output:\n" + actual);
    }
}
