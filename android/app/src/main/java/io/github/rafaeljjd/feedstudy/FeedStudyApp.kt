package io.github.rafaeljjd.feedstudy

import android.app.Application
import io.github.rafaeljjd.feedstudy.data.StudyDatabase
import io.github.rafaeljjd.feedstudy.net.ApiClient
import io.github.rafaeljjd.feedstudy.study.StudyRepository
import io.github.rafaeljjd.feedstudy.usage.UsageReader
import io.github.rafaeljjd.feedstudy.work.Scheduler

class FeedStudyApp : Application() {
    val repository: StudyRepository by lazy {
        StudyRepository(
            context = this,
            db = StudyDatabase.create(this),
            api = ApiClient(BuildConfig.API_BASE_URL),
            usageReader = UsageReader(this),
            scheduler = Scheduler(this),
        )
    }
}
