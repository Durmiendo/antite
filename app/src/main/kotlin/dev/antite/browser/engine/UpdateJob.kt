package dev.antite.browser.engine

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "engine-update") {
            try {
                EngineUpdates.runInBackground(this)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = params.jobId == EngineUpdates.JOB_DOWNLOAD
}
