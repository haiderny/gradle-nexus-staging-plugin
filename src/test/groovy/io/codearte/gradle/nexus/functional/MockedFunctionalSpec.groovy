package io.codearte.gradle.nexus.functional

import com.github.tomakehurst.wiremock.junit.WireMockRule
import com.github.tomakehurst.wiremock.stubbing.Scenario
import groovy.json.JsonOutput
import io.codearte.gradle.nexus.logic.FetcherResponseTrait
import io.codearte.gradle.nexus.logic.RepositoryState
import nebula.test.functional.ExecutionResult
import org.gradle.api.logging.LogLevel
import org.junit.Rule
import spock.lang.Ignore

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.containing
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.verify

class MockedFunctionalSpec extends BaseNexusStagingFunctionalSpec implements FetcherResponseTrait {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(8089)

    private static final String stagingProfileId = "5027d084a01a3a"
    private static final String REPO_ID_1 = "testRepo1"
    private static final String REPO_ID_2 = "testRepo2"

    def "should not do request for staging profile when provided in configuration on #testedTaskName task"() {
        given:
            stubGetOneRepositoryWithProfileIdAndContent(stagingProfileId,
                    createResponseMapWithGivenRepos([aRepoInStateAndId(repoTypeToReturn, REPO_ID_1)]))
        and:
            stubGetRepositoryStateByIdForConsecutiveStates(REPO_ID_1, repositoryStatesToGetById)
        and:
            stubSuccessfulCloseRepositoryWithProfileId(stagingProfileId)
        and:
            stubSuccessfulPromoteRepositoryWithProfileId(stagingProfileId)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
                nexusStaging {
                    stagingProfileId = "$stagingProfileId"
                }
            """.stripIndent()
        when:
            ExecutionResult result = runTasksSuccessfully(testedTaskName)
        then:
            result.wasExecuted(testedTaskName)
            result.standardOutput.contains("Using configured staging profile id: $stagingProfileId")
            !result.standardOutput.contains("Getting staging profile for package group")
        and:
            verify(0, getRequestedFor(urlEqualTo("/staging/profiles")))
        where:
            testedTaskName      | repoTypeToReturn | repositoryStatesToGetById
            "closeRepository"   | "open"           | [RepositoryState.CLOSED]
            "promoteRepository" | "closed"         | [RepositoryState.RELEASED]
    }

    def "should send request for staging profile when not provided in configuration on #testedTaskName task"() {
        given:
            stubGetStagingProfilesWithJson(this.getClass().getResource("/io/codearte/gradle/nexus/logic/2stagingProfilesShrunkResponse.json").text)
        and:
            stubGetOneRepositoryWithProfileIdAndContent(stagingProfileId,
                    createResponseMapWithGivenRepos([aRepoInStateAndId(repoTypeToReturn, REPO_ID_1)]))
        and:
            stubGetRepositoryStateByIdForConsecutiveStates(REPO_ID_1, repositoryStatesToGetById)
        and:
            stubSuccessfulCloseRepositoryWithProfileId(stagingProfileId)
        and:
            stubSuccessfulPromoteRepositoryWithProfileId(stagingProfileId)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
            """.stripIndent()
        when:
            ExecutionResult result = runTasksSuccessfully(testedTaskName)
        then:
            result.wasExecuted(testedTaskName)
            !result.standardOutput.contains("Using configured staging profile id: $stagingProfileId")
            result.standardOutput.contains("Getting staging profile for package group")
        and:
            verify(1, getRequestedFor(urlEqualTo("/staging/profiles")))
        where:
            testedTaskName      | repoTypeToReturn | repositoryStatesToGetById
            "closeRepository"   | "open"           | [RepositoryState.CLOSED]
            "promoteRepository" | "closed"         | [RepositoryState.RELEASED]
    }

    //TODO: Remove when 'stagingProfileId AND stagingRepositoryId' case is fixed
    def "should reuse stagingProfileId from closeRepository in promoteRepository when called together"() {
        given:
            stubGetStagingProfilesWithJson(getClass().getResource("/io/codearte/gradle/nexus/logic/2stagingProfilesShrunkResponse.json").text)
        and:
            stubGetOneOpenRepositoryInFirstCallAndOneClosedInTheNext(stagingProfileId)  //TODO: Just one call
        and:
            //TODO: Should be in scenario with others?
            stubGetRepositoryStateByIdForConsecutiveStates(REPO_ID_1, [RepositoryState.CLOSED, RepositoryState.RELEASED])
        and:
            stubSuccessfulCloseRepositoryWithProfileId(stagingProfileId)
        and:
            stubSuccessfulPromoteRepositoryWithProfileId(stagingProfileId)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
            """.stripIndent()
        when:
            ExecutionResult result = runTasksSuccessfully("closeRepository", "promoteRepository")
        then:
            result.wasExecuted("closeRepository")
            result.wasExecuted("promoteRepository")
        and:
            verify(2, getRequestedFor(urlEqualTo("/staging/profile_repositories/$stagingProfileId")))
            verify(1, getRequestedFor(urlEqualTo("/staging/profiles")))
    }

    @Ignore("Due to https://github.com/Codearte/gradle-nexus-staging-plugin/issues/44")
    def "should reuse stagingProfileId AND stagingRepositoryId from closeRepository in promoteRepository when called together"() {
        given:
            stubGetStagingProfilesWithJson(this.getClass().getResource("/io/codearte/gradle/nexus/logic/2stagingProfilesShrunkResponse.json").text)
        and:
            stubGetOneOpenRepositoryAndOneClosedInFirstCallAndTwoClosedInTheNext(stagingProfileId)
        and:
            stubSuccessfulCloseRepositoryWithProfileId(stagingProfileId)
        and:
            stubSuccessfulPromoteRepositoryWithProfileId(stagingProfileId)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
            """.stripIndent()
        when:
            ExecutionResult result = runTasksSuccessfully("closeRepository", "promoteRepository")
        then:
            result.wasExecuted("closeRepository")
            result.wasExecuted("promoteRepository")
        and:
            verify(1, getRequestedFor(urlEqualTo("/staging/profile_repositories/$stagingProfileId")))
            verify(1, getRequestedFor(urlEqualTo("/staging/profiles")))
    }

    def "should pass parameter to other task"() {
        given:
            stubGetStagingProfilesWithJson(this.getClass().getResource("/io/codearte/gradle/nexus/logic/2stagingProfilesShrunkResponse.json").text)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
                task getValue << {
                    assert getStagingProfile.stagingProfileId == "$stagingProfileId"
                }
            """.stripIndent()
        expect:
            runTasksSuccessfully('getStagingProfile', 'getValue')
    }

    def "should retry promotion when repository has not been already closed"() {
        given:
            stubGetOneOpenRepositoryInFirstCallAndOneClosedInTheNext(stagingProfileId)
        and:
            stubGetRepositoryStateByIdForConsecutiveStates(REPO_ID_1, [RepositoryState.RELEASED])
        and:
            stubSuccessfulCloseRepositoryWithProfileId(stagingProfileId)
        and:
            stubSuccessfulPromoteRepositoryWithProfileId(stagingProfileId)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
                nexusStaging {
                    stagingProfileId = "$stagingProfileId"
                    delayBetweenRetriesInMillis = 50
                    numberOfRetries = 2
                }
            """.stripIndent()
        when:
            ExecutionResult result = runTasksSuccessfully("promoteRepository")
        then:
            result.wasExecuted("promoteRepository")
            result.standardOutput.contains("Attempt 1/3 failed.")
            !result.standardOutput.contains("Attempt 2/3 failed.")
    }

    def "should display staging profile without --info switch"() {
        given:
            stubGetStagingProfilesWithJson(this.getClass().getResource("/io/codearte/gradle/nexus/logic/2stagingProfilesShrunkResponse.json").text)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
            """.stripIndent()
        and:
            logLevel = LogLevel.LIFECYCLE
        when:
            ExecutionResult result = runTasksSuccessfully('getStagingProfile')
        then:
            result.standardOutput.contains("Received staging profile id: $stagingProfileId")
    }

    def "should call close and promote in closeAndPromoteRepository task"() {
        given:
//            stubGetOneOpenRepositoryAndOneClosedInFirstCallAndTwoClosedInTheNext(stagingProfileId)    //TODO: Temporary disabled due to #44
            stubGetOneOpenRepositoryInFirstCallAndOneClosedInTheNext(stagingProfileId)
        and:
            stubGetRepositoryStateByIdForConsecutiveStates(REPO_ID_1, [RepositoryState.CLOSED, RepositoryState.RELEASED])
        and:
            stubSuccessfulCloseRepositoryWithProfileId(stagingProfileId)
        and:
            stubSuccessfulPromoteRepositoryWithProfileId(stagingProfileId)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
                nexusStaging {
                    stagingProfileId = "$stagingProfileId"
                }
            """.stripIndent()
        when:
            ExecutionResult result = runTasksSuccessfully("closeAndPromoteRepository")
        then:
            result.wasExecuted("closeRepository")
            result.wasExecuted("promoteRepository")
            result.wasExecuted("closeAndPromoteRepository")
    }

    def "packageGroup should be set to project.group by default "() {
        given:
            stubGetStagingProfilesWithJson(this.getClass().getResource("/io/codearte/gradle/nexus/logic/2stagingProfilesShrunkResponse.json").text)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                nexusStaging {
                    serverUrl = "http://localhost:8089/"
                }
                project.group = "io.codearte"
            """.stripIndent()
        when:
            ExecutionResult result = runTasksSuccessfully('getStagingProfile')
        then:
            result.standardOutput.contains("Received staging profile id: $stagingProfileId")
    }

    def "explicitly defined packageGroup should override default value"() {
        given:
            stubGetStagingProfilesWithJson(this.getClass().getResource("/io/codearte/gradle/nexus/logic/2stagingProfilesShrunkResponse.json").text)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
                project.group = "io.someother"
            """.stripIndent()
        when:
            ExecutionResult result = runTasksSuccessfully('getStagingProfile')
        then:
            result.standardOutput.contains("Received staging profile id: $stagingProfileId")
    }

    @Override
    protected String getDefaultConfigurationClosure() {
        return """
                nexusStaging {
                    username = "codearte"
                    packageGroup = "io.codearte"
                    serverUrl = "http://localhost:8089/"
                }
        """
    }

    private void stubGetStagingProfilesWithJson(String responseAsJson) {
        stubFor(get(urlEqualTo("/staging/profiles"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseAsJson)))
    }

    private void stubGetOneRepositoryWithProfileIdAndContent(String stagingProfileId, Map response) {
        stubFor(get(urlEqualTo("/staging/profile_repositories/$stagingProfileId"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(JsonOutput.prettyPrint(JsonOutput.toJson(response)))))
    }

    private void stubGetRepositoryStateByIdForConsecutiveStates(String repoId, List<RepositoryState> repoStates) {
        repoStates.eachWithIndex { repoState, index ->
            stubFor(get(urlEqualTo("/staging/repository/$repoId"))
                .inScenario("StateById")
                .whenScenarioStateIs(index == 0 ? Scenario.STARTED : repoStates[index].name())
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(JsonOutput.prettyPrint(JsonOutput.toJson(aRepoInStateAndIdFull(repoId, repoState)))))
                .willSetStateTo(repoStates[index < repoStates.size() - 1 ? index + 1 : index].name()))  //TODO: Simplify/extract...
        }
    }

    private void stubSuccessfulCloseRepositoryWithProfileId(String stagingProfileId) {
        stubGivenSuccessfulTransitionOperationWithProfileId("finish", stagingProfileId)
    }

    private void stubSuccessfulPromoteRepositoryWithProfileId(String stagingProfileId) {
        stubGivenSuccessfulTransitionOperationWithProfileId("promote", stagingProfileId)
    }

    private void stubGivenSuccessfulTransitionOperationWithProfileId(String restCommandName, String stagingProfileId) {
        stubFor(post(urlEqualTo("/staging/profiles/$stagingProfileId/$restCommandName"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Accept", containing("application/json"))
                //TODO: Content matching
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")))
    }

    private void stubGetOneOpenRepositoryAndOneClosedInFirstCallAndTwoClosedInTheNext(String stagingProfileId) {
        stubGetGivenRepositoriesInFirstAndSecondCall(stagingProfileId,
                [aRepoInStateAndId("open", "ignored"), aRepoInStateAndId("closed", "ignoredClosed")],
                [aRepoInStateAndId("closed", "ignored"), aRepoInStateAndId("closed", "ignoredClosed")])
    }

    private void stubGetOneOpenRepositoryInFirstCallAndOneClosedInTheNext(String stagingProfileId) {
        stubGetGivenRepositoriesInFirstAndSecondCall(stagingProfileId,
                [aRepoInStateAndId("open", REPO_ID_1)],
                [aRepoInStateAndId("closed", REPO_ID_1)])
    }

    private void stubGetGivenRepositoriesInFirstAndSecondCall(String stagingProfileId, List<Map> repositoriesToReturnInFirstCall,
                                                              List<Map> repositoriesToReturnInSecondCall) {
        stubFor(get(urlEqualTo("/staging/profile_repositories/$stagingProfileId")).inScenario("State")
            .whenScenarioStateIs(Scenario.STARTED)
            .withHeader("Content-Type", containing("application/json"))
            .withHeader("Accept", containing("application/json"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    JsonOutput.prettyPrint(JsonOutput.toJson(createResponseMapWithGivenRepos(repositoriesToReturnInFirstCall)))
                )
            )
            .willSetStateTo("CLOSED"))

        stubFor(get(urlEqualTo("/staging/profile_repositories/$stagingProfileId")).inScenario("State")
            .whenScenarioStateIs("CLOSED")
            .withHeader("Content-Type", containing("application/json"))
            .withHeader("Accept", containing("application/json"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    JsonOutput.prettyPrint(JsonOutput.toJson(createResponseMapWithGivenRepos(repositoriesToReturnInSecondCall)))
                )
            )
        )
    }
}
