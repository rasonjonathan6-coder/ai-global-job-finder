package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.applications.ApplicationCenter
import com.aiglobal.jobfinder.data.applications.ApplicationStore
import com.aiglobal.jobfinder.data.cv.CvCompleteness
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CvRecord
import com.aiglobal.jobfinder.data.model.CvStatus
import com.aiglobal.jobfinder.data.model.EducationEntry
import com.aiglobal.jobfinder.data.model.buildUserProfile
import com.aiglobal.jobfinder.data.repository.SampleJobRepository
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Guards the Stage 12 rule: production paths never invent the candidate or their
 * applications. The sample candidate and sample applications exist only behind demo mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProductionDataTest {

    @Test
    fun profileIsEmptyUntilACvIsImported() {
        val profile = buildUserProfile(record = null, autoApplyEnabled = false)

        assertTrue("A fresh profile has no invented name", profile.fullName.isBlank())
        assertTrue("A fresh profile claims no skills", profile.skills.isEmpty())
        assertTrue("A fresh profile claims no experience", profile.experience.isEmpty())
        assertEquals(0, profile.yearsOfExperience)
        assertEquals(false, profile.cvImported)
    }

    @Test
    fun profileIsDerivedFromTheImportedCv() {
        val record = CvRecord(
            fileName = "cv.pdf",
            storedFileName = "cv.pdf",
            fileType = com.aiglobal.jobfinder.data.model.CvFileType.PDF,
            sizeBytes = 1024,
            importedAtEpochMillis = 1_000,
            analyzedAtEpochMillis = 2_000,
            status = CvStatus.ANALYZED,
            profile = CandidateProfile(
                fullName = "Aina Rasoa",
                currentTitle = "Welder",
                location = "Toamasina, Madagascar",
                yearsOfExperience = 7,
                skills = listOf("MIG welding"),
                education = listOf(EducationEntry("BEP Welding", "CFP Toamasina", "Madagascar", 2015)),
            ),
        )

        val profile = buildUserProfile(record, autoApplyEnabled = true)

        assertEquals("Aina Rasoa", profile.fullName)
        assertEquals("Welder", profile.headline)
        assertEquals(7, profile.yearsOfExperience)
        assertTrue(profile.cvImported)
        assertTrue(profile.autoApplyEnabled)
        assertEquals(CvCompleteness.of(record.profile).percent, profile.profileCompletion)
    }

    @Test
    fun productionStartsWithNoApplications() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(context.cacheDir, "production-test-applications-${System.nanoTime()}.json")
        val store = ApplicationStore(context, file)
        val center = ApplicationCenter(
            repository = SampleJobRepository(refreshDelayMillis = 0),
            store = store,
            // Production default: demo mode off.
            demoMode = flowOf(false),
        )

        val settled = runBlocking {
            // Give the seed check a moment; it must never fire without demo mode.
            withTimeoutOrNull(2.seconds) {
                while (center.applications.value.isEmpty()) {
                    kotlinx.coroutines.delay(50)
                }
                true
            }
        }

        assertEquals(null, settled)
        assertTrue(center.applications.value.isEmpty())
        file.delete()
    }
}
