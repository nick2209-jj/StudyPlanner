package com.example.studyplanner

import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.studyplanner.databinding.FragmentHomeBinding
import com.example.studyplanner.model.FreeTimeSlot
import com.example.studyplanner.model.ScheduleSession
import com.example.studyplanner.model.Task
import com.example.studyplanner.repository.AuthRepository
import com.example.studyplanner.repository.FreeTimeRepository
import com.example.studyplanner.repository.ScheduleRepository
import com.example.studyplanner.repository.SubjectRepository
import com.example.studyplanner.repository.TaskRepository
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val authRepository = AuthRepository()
    private val taskRepository = TaskRepository()
    private val subjectRepository = SubjectRepository()
    private val scheduleRepository = ScheduleRepository()
    private val freeTimeRepository = FreeTimeRepository()
    private var tasksListener: ListenerRegistration? = null
    private var subjectsListener: ListenerRegistration? = null
    private var scheduleListener: ListenerRegistration? = null
    private var freeTimeListener: ListenerRegistration? = null
    private var dashboardTasks: List<Task> = emptyList()
    private var dashboardSessions: List<ScheduleSession> = emptyList()
    private var dashboardFreeTimeSlots: List<FreeTimeSlot> = emptyList()
    private var tasksLoaded = false
    private var scheduleLoaded = false
    private var freeTimeLoaded = false
    private var taskLoadFailed = false
    private var scheduleLoadFailed = false
    private var progressAnimator: ValueAnimator? = null
    private var quoteLoadAttemptDate: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        runEntranceAnimation()
        binding.subjectsCard.setOnClickListener {
            findNavController().navigate(R.id.subjectsFragment)
        }
        binding.pendingCard.setOnClickListener {
            findNavController().navigate(R.id.tasksFragment)
        }
        binding.scheduleCard.setOnClickListener {
            findNavController().navigate(R.id.scheduleFragment)
        }
        binding.upNextCard.setOnClickListener {
            findNavController().navigate(R.id.scheduleFragment)
        }
        binding.freeTimeCard.setOnClickListener {
            findNavController().navigate(R.id.freeTimeFragment)
        }
        loadGreeting()
        updateDashboardDate()
        showQuote(getOfflineQuote(LocalDate.now()), getString(R.string.quote_offline_source))
        loadDailyQuote()
        observeDashboardData()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updateDashboardDate()
            refreshTodayProgress()
            refreshScheduleSummary()
            refreshFreeTimeSummary()
            loadDailyQuote()
        }
    }

    private fun loadGreeting() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val profile = authRepository.getCurrentUser()
                val name = profile?.name?.takeIf { it.isNotBlank() }
                    ?: authRepository.currentAuthUserName()?.takeIf { it.isNotBlank() }
                    ?: "Student"
                val greeting = when (LocalTime.now().hour) {
                    in 5..11 -> "Good morning"
                    in 12..16 -> "Good afternoon"
                    else -> "Good evening"
                }
                binding.tvGreeting.text = "$greeting, $name!"
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Toast.makeText(
                    requireContext(),
                    "Could not load your profile: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun observeDashboardData() {
        tasksListener = taskRepository.observeTasks(
            onUpdate = { tasks ->
                if (_binding != null) {
                    dashboardTasks = tasks
                    tasksLoaded = true
                    taskLoadFailed = false
                    refreshTodayProgress()
                }
            },
            onError = { exception ->
                if (_binding != null) {
                    tasksLoaded = true
                    taskLoadFailed = true
                    refreshTodayProgress()
                }
                showLoadError("tasks", exception)
            }
        )
        subjectsListener = subjectRepository.observeSubjects(
            onUpdate = { subjects ->
                if (_binding != null) binding.tvSubjectsCount.text = subjects.size.toString()
            },
            onError = { exception ->
                if (_binding != null) binding.tvSubjectsCount.setText(R.string.not_available)
                showLoadError("subjects", exception)
            }
        )
        val startDate = LocalDate.now()
        scheduleListener = scheduleRepository.observeSessions(
            startDate.toString(),
            startDate.plusDays(6).toString(),
            onUpdate = { sessions ->
                if (_binding != null) {
                    dashboardSessions = sessions
                    scheduleLoaded = true
                    scheduleLoadFailed = false
                    refreshTodayProgress()
                    refreshScheduleSummary()
                }
            },
            onError = { exception ->
                if (_binding != null) {
                    scheduleLoaded = true
                    scheduleLoadFailed = true
                    binding.tvScheduleCount.setText(R.string.not_available)
                    binding.tvUpNextTitle.setText(R.string.schedule_unavailable)
                    binding.tvUpNextTime.setText(R.string.schedule_unavailable)
                    refreshTodayProgress()
                }
                showLoadError("schedule", exception)
            }
        )
        freeTimeListener = freeTimeRepository.observeSlots(
            onUpdate = { slots ->
                if (_binding != null) {
                    dashboardFreeTimeSlots = slots
                    freeTimeLoaded = true
                    refreshFreeTimeSummary()
                }
            },
            onError = { exception ->
                if (_binding != null) {
                    freeTimeLoaded = true
                    binding.tvFreeTimeSummary.setText(R.string.availability_unavailable)
                }
                showLoadError("free time", exception)
            }
        )
    }

    private fun showLoadError(section: String, exception: Exception) {
        if (_binding != null) {
            Toast.makeText(
                requireContext(),
                "Could not load $section: ${exception.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun refreshTodayProgress() {
        if (!tasksLoaded || !scheduleLoaded) {
            binding.tvProgressSub.setText(R.string.loading_today_progress)
            binding.tvProgressPercent.setText(R.string.not_available)
            binding.tvPendingCount.setText(R.string.not_available)
            return
        }
        if (taskLoadFailed || scheduleLoadFailed) {
            binding.tvProgressSub.setText(R.string.progress_unavailable)
            binding.tvProgressPercent.setText(R.string.not_available)
            binding.tvPendingCount.setText(R.string.not_available)
            progressAnimator?.cancel()
            binding.circularProgress.progress = 0
            return
        }

        val today = LocalDate.now()
        val todayTasks = dashboardTasks.filter { task ->
            if (task.dueTime.isBlank()) return@filter true
            try {
                LocalDate.parse(task.dueTime) == today
            } catch (_: DateTimeParseException) {
                try {
                    LocalTime.parse(task.dueTime)
                    true
                } catch (_: DateTimeParseException) {
                    true
                }
            }
        }
        val todayGeneratedSessions = dashboardSessions.filter {
            it.sessionDate == today.toString() && it.generatedActivityKey.isNotBlank()
        }
        val completed = todayTasks.count { it.isCompleted } +
            todayGeneratedSessions.count { it.isCompleted }
        val total = todayTasks.size + todayGeneratedSessions.size
        binding.tvPendingCount.text = (total - completed).toString()
        animateProgressRing(completed, total)
    }

    private fun updateDashboardDate() {
        binding.tvDate.text =
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, dd MMMM", Locale.getDefault()))
    }

    private fun refreshScheduleSummary() {
        if (!scheduleLoaded || scheduleLoadFailed) return
        val today = LocalDate.now()
        val todayText = today.toString()
        val todaySessions = dashboardSessions.filter { it.sessionDate == todayText }
        binding.tvScheduleCount.text = todaySessions.size.toString()
        val now = LocalTime.now().hour * 60 + LocalTime.now().minute
        val upcoming = dashboardSessions.firstOrNull { session ->
            !session.isCompleted && (
                session.sessionDate > todayText ||
                    (session.sessionDate == todayText && session.endMinutes > now)
                )
        }
        if (upcoming == null) {
            binding.tvUpNextTitle.setText(R.string.no_upcoming_sessions)
            binding.tvUpNextTime.setText(R.string.add_session_to_schedule)
        } else {
            binding.tvUpNextTitle.text = upcoming.description.ifBlank { upcoming.subjectName }
            val date = LocalDate.parse(upcoming.sessionDate)
            val day = if (date == today) "Today" else
                date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
            binding.tvUpNextTime.text =
                "$day · ${formatTime(upcoming.startMinutes)}–${formatTime(upcoming.endMinutes)}"
        }
    }

    private fun refreshFreeTimeSummary() {
        if (!freeTimeLoaded) return
        val today = LocalDate.now()
        val todaySlots = dashboardFreeTimeSlots.count { slot ->
            if (slot.isRecurring) {
                slot.dayOfWeek == today.dayOfWeek.value
            } else {
                slot.date == today.toString()
            }
        }
        binding.tvFreeTimeSummary.text = if (todaySlots == 0) {
            getString(R.string.add_available_study_times)
        } else {
            getString(
                R.string.today_availability_summary,
                todaySlots,
                resources.getQuantityString(R.plurals.study_time_slots, todaySlots)
            )
        }
    }

    private fun loadDailyQuote() {
        val today = LocalDate.now().toString()
        val preferences = requireContext().getSharedPreferences(QUOTE_PREFERENCES, Context.MODE_PRIVATE)
        val cachedDate = preferences.getString(KEY_QUOTE_DATE, null)
        val cachedQuote = preferences.getString(KEY_QUOTE_TEXT, null)
        val cachedAuthor = preferences.getString(KEY_QUOTE_AUTHOR, null)
        if (cachedDate == today && !cachedQuote.isNullOrBlank() && !cachedAuthor.isNullOrBlank()) {
            quoteLoadAttemptDate = today
            showQuote(DailyQuote(cachedQuote, cachedAuthor), getString(R.string.quote_daily_source))
            return
        }
        if (quoteLoadAttemptDate == today) return
        quoteLoadAttemptDate = today
        showQuote(getOfflineQuote(LocalDate.now()), getString(R.string.quote_offline_source))

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val quote = withContext(Dispatchers.IO) { fetchDailyQuote() }
                preferences.edit()
                    .putString(KEY_QUOTE_DATE, today)
                    .putString(KEY_QUOTE_TEXT, quote.text)
                    .putString(KEY_QUOTE_AUTHOR, quote.author)
                    .apply()
                if (_binding != null) {
                    showQuote(quote, getString(R.string.quote_daily_source))
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Log.w(TAG, "Could not fetch today's quote; showing the offline quote", exception)
            }
        }
    }

    private suspend fun fetchDailyQuote(): DailyQuote {
        val connection = URL(QUOTE_API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = QUOTE_TIMEOUT_MILLIS
            connection.readTimeout = QUOTE_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "StudyPlanner/1.0")
            if (connection.responseCode !in 200..299) {
                throw IOException("Quote service returned HTTP ${connection.responseCode}")
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val quoteJson = JSONArray(response).optJSONObject(0)
                ?: throw IOException("Quote service returned no quote")
            val text = quoteJson.optString("q").trim()
            val author = quoteJson.optString("a").trim()
            if (text.isBlank() || author.isBlank()) {
                throw IOException("Quote service returned incomplete quote data")
            }
            return DailyQuote(text, author)
        } finally {
            connection.disconnect()
        }
    }

    private fun showQuote(quote: DailyQuote, source: String) {
        if (_binding == null) return
        binding.tvDailyQuote.text = "“${quote.text}”"
        binding.tvQuoteAuthor.text = "— ${quote.author}"
        binding.tvQuoteSource.text = source
    }

    private fun getOfflineQuote(date: LocalDate): DailyQuote {
        val quotes = listOf(
            "Small, steady steps turn a plan into progress." to "StudyPlanner",
            "Focus on the next task, not the whole mountain." to "StudyPlanner",
            "Every page you study is a step toward your goal." to "StudyPlanner",
            "A little progress each day adds up to big results." to "StudyPlanner",
            "Make today count, one focused moment at a time." to "StudyPlanner"
        )
        val quoteIndex = (date.dayOfYear - 1) % quotes.size
        return DailyQuote(quotes[quoteIndex].first, quotes[quoteIndex].second)
    }

    private fun formatTime(minutes: Int): String {
        val hour = minutes / 60
        val minute = minutes % 60
        val suffix = if (hour < 12) "AM" else "PM"
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        return "%d:%02d %s".format(hour12, minute, suffix)
    }

    /** Staggered fade + slide-up for each section, top to bottom. */
    private fun runEntranceAnimation() {
        val sections = listOf(
            binding.tvGreeting,
            binding.tvDate,
            binding.progressCard,
            binding.statsRow,
            binding.tvUpNextLabel,
            binding.upNextCard,
            binding.freeTimeCard,
            binding.quoteCard
        )

        sections.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 40f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(60L + index * 70L)
                .setDuration(400L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** Animates the circular progress ring filling up + percentage counting, instead of snapping instantly. */
    private fun animateProgressRing(completed: Int, total: Int) {
        val percent = if (total == 0) 0 else completed * 100 / total

        binding.tvProgressSub.text = if (total == 0) {
            getString(R.string.no_tasks_scheduled_today)
        } else {
            getString(R.string.today_progress_summary, completed, total)
        }

        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofInt(binding.circularProgress.progress, percent).apply {
            duration = 900L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                binding.circularProgress.progress = value
                binding.tvProgressPercent.text = "$value%"
            }
            start()
        }
    }

    private data class DailyQuote(val text: String, val author: String)

    override fun onDestroyView() {
        progressAnimator?.cancel()
        progressAnimator = null
        tasksListener?.remove()
        tasksListener = null
        subjectsListener?.remove()
        subjectsListener = null
        scheduleListener?.remove()
        scheduleListener = null
        freeTimeListener?.remove()
        freeTimeListener = null
        dashboardTasks = emptyList()
        dashboardSessions = emptyList()
        dashboardFreeTimeSlots = emptyList()
        tasksLoaded = false
        scheduleLoaded = false
        freeTimeLoaded = false
        taskLoadFailed = false
        scheduleLoadFailed = false
        quoteLoadAttemptDate = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "HomeFragment"
        private const val QUOTE_API_URL = "https://zenquotes.io/api/today"
        private const val QUOTE_TIMEOUT_MILLIS = 5_000
        private const val QUOTE_PREFERENCES = "daily_quote_cache"
        private const val KEY_QUOTE_DATE = "date"
        private const val KEY_QUOTE_TEXT = "text"
        private const val KEY_QUOTE_AUTHOR = "author"
    }
}