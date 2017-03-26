package io.codearte.gradle.nexus

import groovy.transform.CompileStatic
import io.codearte.gradle.nexus.logic.OperationRetrier
import io.codearte.gradle.nexus.logic.RepositoryFetcher
import io.codearte.gradle.nexus.logic.RepositoryPromoter
import io.codearte.gradle.nexus.logic.RepositoryState
import io.codearte.gradle.nexus.logic.RepositoryStateFetcher
import io.codearte.gradle.nexus.logic.RetryingRepositoryTransitioner
import io.codearte.gradle.nexus.logic.StagingProfileFetcher
import org.gradle.api.tasks.TaskAction

@CompileStatic
class PromoteRepositoryTask extends BaseStagingTask {

    @TaskAction
    void doAction() {
        StagingProfileFetcher stagingProfileFetcher = createFetcherWithGivenClient(createClient())
        RepositoryFetcher repositoryFetcher = createRepositoryFetcherWithGivenClient(createClient())

        tryToTakeStagingProfileIdFromCloseRepositoryTask()
        String stagingProfileId = fetchAndCacheStagingProfileId(stagingProfileFetcher)

        String repositoryId = getRepositoryIdFromCloseTaskOrFromServer(stagingProfileId, repositoryFetcher)

        promoteRepositoryByIdAndProfileIdWithRetrying(repositoryId, stagingProfileId)
    }

    private void tryToTakeStagingProfileIdFromCloseRepositoryTask() {
        if (getStagingProfileId() != null) {
            return
        }
        String stagingProfileIdFromCloseRepositoryTask = getCloseRepositoryTask().stagingProfileId

        if (stagingProfileIdFromCloseRepositoryTask != null) {
            logger.debug("Reusing staging profile id from closeRepository task: $stagingProfileIdFromCloseRepositoryTask")
            setStagingProfileId(stagingProfileIdFromCloseRepositoryTask)
        }
    }

    private CloseRepositoryTask getCloseRepositoryTask() {
        return project.tasks.withType(CloseRepositoryTask)[0]
    }

    private String getRepositoryIdFromCloseTaskOrFromServer(String stagingProfileId, RepositoryFetcher repositoryFetcher) {
        //TODO: Add debug statement
        OperationRetrier<String> retrier = createOperationRetrier()
        String repositoryId = /*getCloseRepositoryTask().stagingRepositoryId ?:*/   //Temporary disabled due to https://github.com/Codearte/gradle-nexus-staging-plugin/issues/44
                retrier.doWithRetry { repositoryFetcher.getClosedRepositoryIdForStagingProfileId(stagingProfileId) }
        return repositoryId
    }

    private void promoteRepositoryByIdAndProfileIdWithRetrying(String repositoryId, String stagingProfileId) {
        RepositoryPromoter repositoryPromoter = createRepositoryPromoterWithGivenClient(createClient())
        RepositoryStateFetcher repositoryStateFetcher = createRepositoryStateFetcherWithGivenClient(createClient())
        OperationRetrier<RepositoryState> retrier = createOperationRetrier()
        RetryingRepositoryTransitioner retryingPromoter = new RetryingRepositoryTransitioner(repositoryPromoter, repositoryStateFetcher, retrier)

        retryingPromoter.performWithRepositoryIdAndStagingProfileId(repositoryId, stagingProfileId)
    }
}
