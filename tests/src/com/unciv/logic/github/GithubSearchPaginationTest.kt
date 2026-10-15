package com.unciv.logic.github

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubSearchPaginationTest {
    private fun result(total: Int, returned: Int) = GithubAPI.RepoSearch().apply {
        total_count = total
        repeat(returned) { items.add(GithubAPI.Repo()) }
    }

    @Test fun fullPageContinuesBeforeTheSearchWindowEnds() {
        assertTrue(GithubAPI.hasMoreSearchPages(1, 100, result(1358, 100)))
        assertTrue(GithubAPI.hasMoreSearchPages(9, 100, result(1358, 100)))
    }

    @Test fun searchWindowStopsBeforeTheRejectedEleventhPage() {
        assertFalse(GithubAPI.hasMoreSearchPages(10, 100, result(1358, 100)))
        assertFalse(GithubAPI.hasMoreSearchPages(20, 50, result(1358, 50)))
    }

    @Test fun exactTotalDoesNotRequestAnExtraPage() {
        assertFalse(GithubAPI.hasMoreSearchPages(2, 100, result(200, 100)))
    }

    @Test fun partialAndEmptyResultsFinishTheSearch() {
        assertFalse(GithubAPI.hasMoreSearchPages(2, 100, result(145, 45)))
        assertFalse(GithubAPI.hasMoreSearchPages(1, 100, result(0, 0)))
    }
}
