package com.yantkys.memonow

import com.yantkys.memonow.backup.backupCapacityWarning
import com.yantkys.memonow.data.Post
import org.junit.Assert.*
import org.junit.Test

class BackupCapacityTest {
    private val post = Post(id = "fixture", body = "短い一文", createdAt = 0)
    @Test fun ordinaryHistoryDoesNotShowAnUpperLimitWarning() {
        assertNull(backupCapacityWarning(emptyList()))
        assertNull(backupCapacityWarning(List(100) { post }))
    }
    @Test fun countLimitIsReportedBeforeTheNextPostExceedsIt() {
        assertTrue(backupCapacityWarning(List(10_000) { post })!!.contains("1万件"))
        assertTrue(backupCapacityWarning(List(10_001) { post })!!.contains("できません"))
    }
    @Test fun largeTextShowsConservativeSizeWarningWithoutBlockingPosts() {
        assertTrue(backupCapacityWarning(List(20) { post.copy(body = "x".repeat(100_000)) })!!.contains("概算"))
    }
}
