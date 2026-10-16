package com.example.studyplanner

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.studyplanner.databinding.FragmentHomeBinding
import com.example.studyplanner.repository.AuthRepository
import com.example.studyplanner.repository.FreeTimeRepository
import com.example.studyplanner.repository.ScheduleRepository
import com.example.studyplanner.repository.SubjectRepository
import com.example.studyplanner.repository.TaskRepository
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        runEntranceAnimation()
        binding.tvDate.text = SimpleDateFormat("EEEE, dd MMMM", Locale.getDefault()).format(Date())
        binding.subjectsCard.setOnClickListener {
            findNavController().navigate(R.id.subjectsFragment)
        }
        binding.upNextCard.setOnClickListener {
            findNavController().navigate(R.id.scheduleFragment)
        }
        binding.freeTimeCard.setOnClickListener {
            findNavController().navigate(R.id.freeTimeFragment)
        }
        loadGreeting()
        observeDashboardData()
    }

    private fun loadGreeting() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val profile = authRepository.getCurrentUser()
                val name = profile?.name?.takeIf { it.isNotBlank() }
                    ?: authRepository.currentAuthUserName()?.takeIf { it.isNotBlank() }
                    ?: "Student"
                binding.tvGreeting.text = "Good morning, $name!"
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
                    val completed = tasks.count { it.isCompleted }
                    binding.tvPendingCount.text = (tasks.size - completed).toString()
                    animateProgressRing(completed, tasks.size)
                }
            },
            onError = { exception -> showLoadError("tasks", exception) }
        )
        subjectsListener = subjectRepository.observeSubjects(
            onUpdate = { subjects ->
                if (_binding != null) binding.tvSubjectsCount.text = subjects.size.toString()
            },
            onError = { exception -> showLoadError("subjects", exception) }
        )
        val startDate = LocalDate.now()
        scheduleListener = scheduleRepository.observeSessions(
            startDate.toString(),
            startDate.plusDays(6).toString(),
            onUpdate = { sessions ->
                if (_binding != null) {
                    binding.tvScheduleCount.text = sessions.size.toString()
                    val now = LocalTime.now().hour * 60 + LocalTime.now().minute
                    val upcoming = sessions.firstOrNull {
                        it.sessionDate > startDate.toString() ||
                            (it.sessionDate == startDate.toString() && it.startMinutes >= now)
                    }
                    if (upcoming == null) {
                        binding.tvUpNextTitle.text = "No upcoming study sessions"
                        binding.tvUpNextTime.text = "Add a session in your schedule"
                    } else {
                        binding.tvUpNextTitle.text =
                            upcoming.description.ifBlank { upcoming.subjectName }
                        val date = LocalDate.parse(upcoming.sessionDate)
                        val day = if (date == startDate) "Today" else
                            date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
                        binding.tvUpNextTime.text =
                            "$day · ${formatTime(upcoming.startMinutes)}–${formatTime(upcoming.endMinutes)}"
                    }
                }
            },
            onError = { exception -> showLoadError("schedule", exception) }
        )
        freeTimeListener = freeTimeRepository.observeSlots(
            onUpdate = { slots ->
                if (_binding != null) {
                    binding.tvFreeTimeSummary.text = if (slots.isEmpty()) {
                        "Add your available study times"
                    } else {
                        "${slots.size} availability slots saved · Tap to manage"
                    }
                }
            },
            onError = { exception -> showLoadError("free time", exception) }
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
            binding.freeTimeCard
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

        binding.tvProgressSub.text = "$completed of $total tasks completed"

        ValueAnimator.ofInt(0, percent).apply {
            duration = 900L
            startDelay = 300L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                binding.circularProgress.progress = value
                binding.tvProgressPercent.text = "$value%"
            }
            start()
        }
    }

    override fun onDestroyView() {
        tasksListener?.remove()
        tasksListener = null
        subjectsListener?.remove()
        subjectsListener = null
        scheduleListener?.remove()
        scheduleListener = null
        freeTimeListener?.remove()
        freeTimeListener = null
        super.onDestroyView()
        _binding = null
    }
}