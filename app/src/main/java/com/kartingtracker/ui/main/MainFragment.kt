package com.kartingtracker.ui.main

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.kartingtracker.BuildConfig
import com.kartingtracker.R
import com.kartingtracker.databinding.FragmentMainBinding
import com.kartingtracker.databinding.FragmentMainBottomBinding
import com.kartingtracker.ui.AppViewModelFactory
import com.kartingtracker.ui.SessionViewModel
import kotlinx.coroutines.launch

class MainFragment : Fragment() {
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private var _bottomBinding: FragmentMainBottomBinding? = null
    private val bottomBinding get() = _bottomBinding!!

    private val sessionViewModel: SessionViewModel by activityViewModels {
        AppViewModelFactory(requireActivity().application)
    }

    private lateinit var trackTileAdapter: TrackTileAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        _bottomBinding = FragmentMainBottomBinding.bind(binding.root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTrackGrid()

        binding.startButton.setOnClickListener { sessionViewModel.startRecording() }
        binding.stopButton.setOnClickListener { sessionViewModel.stopRecording() }
        binding.addTrackButton.setOnClickListener { showAddTrackDialog() }
        binding.generateSeededButton.setOnClickListener {
            sessionViewModel.generateSeededSessionsForSelectedTrack { generatedCount, trackName ->
                val message = if (generatedCount > 0 && trackName.isNotBlank()) {
                    getString(R.string.seeded_sessions_generated, generatedCount, trackName)
                } else {
                    getString(R.string.seeded_sessions_generation_failed)
                }
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }
        }
        binding.generateSeededButton.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        setupComparisonSelectors()
        bottomBinding.detailsButton.setOnClickListener {
            sessionViewModel.setAnalysisMode(com.kartingtracker.ui.AnalysisMode.COMPARISON)
            openComparisonTools()
        }
        bottomBinding.openInsightsButton.setOnClickListener {
            sessionViewModel.setAnalysisMode(com.kartingtracker.ui.AnalysisMode.COACHING)
            openComparisonTools()
        }
        bottomBinding.openTimeLossButton.setOnClickListener {
            sessionViewModel.setAnalysisMode(com.kartingtracker.ui.AnalysisMode.TIME_LOSS)
            openComparisonTools()
        }
        bottomBinding.openSessionsButton.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_sessionListFragment)
        }
        binding.editTrackButton.setOnClickListener { showTrackManagementDialog() }
        bottomBinding.lastSessionCard.setOnClickListener { showDetailsNavigationDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionViewModel.uiState.collect { state ->
                    binding.recordingIndicator.isSelected = state.isRecording || state.isCalibrating || state.isPreparing || state.isStopping
                    binding.recordingIndicator.text = state.statusLabel
                    binding.recordingTimerLabel.text = state.recordingTimerLabel
                    binding.stateHeadline.text = state.stateHeadline
                    binding.stateDetail.text = state.stateDetail
                    binding.preStartCountdownValue.visibility = if (state.showCountdown) View.VISIBLE else View.GONE
                    binding.preStartCountdownValue.text = state.preStartCountdownLabel
                    binding.startButton.isEnabled = !state.isRecording &&
                        !state.isPreparing &&
                        !state.isCalibrating &&
                        !state.isStopping &&
                        state.hasRequiredSensors &&
                        state.hasValidSelectedTrack
                    binding.stopButton.isEnabled = state.isRecording || state.isCalibrating || state.isPreparing || state.isStopping
                    val stopPrimary = state.isRecording || state.isPreparing || state.isCalibrating || state.isStopping
                    binding.startButton.alpha = if (stopPrimary) 0.55f else 1f
                    binding.stopButton.alpha = if (stopPrimary) 1f else 0.7f
                    binding.startButton.text = if (state.isPreparing) getString(R.string.start_pending) else getString(R.string.start_recording_compact)
                    binding.stopButton.text = if (stopPrimary) getString(R.string.stop_recording_now) else getString(R.string.stop_recording_compact)
                    binding.editTrackButton.isEnabled = !state.isRecording && !state.isCalibrating && !state.isPreparing && !state.isStopping && state.hasValidSelectedTrack
                    binding.generateSeededButton.isEnabled =
                        BuildConfig.DEBUG &&
                            !state.isRecording &&
                            !state.isCalibrating &&
                            !state.isPreparing &&
                            !state.isStopping &&
                            state.hasValidSelectedTrack
                    binding.sensorAvailabilityLabel.visibility = if (state.hasRequiredSensors) View.GONE else View.VISIBLE
                    binding.trackProfileLabel.text = state.trackProfileSummary
                    bottomBinding.detailsButton.isEnabled = state.canOpenAnalysis
                    bottomBinding.openInsightsButton.isEnabled = state.canOpenAnalysis
                    bottomBinding.openTimeLossButton.isEnabled = state.canOpenAnalysis
                    bottomBinding.openSessionsButton.isEnabled = true
                    binding.heroTrackValue.text = state.selectedTrackName.ifBlank {
                        getString(R.string.no_track_selected)
                    }
                    bottomBinding.lastSessionHeadline.text = state.lastSessionSummary.headline
                    bottomBinding.lastSessionQuality.text = state.lastSessionSummary.quality
                    bottomBinding.lastSessionTimeLoss.text = state.lastSessionSummary.biggestLoss
                    bottomBinding.lastSessionHint.text = state.lastSessionSummary.coachingHint
                    bottomBinding.lastSessionCornerActions.text = if (state.lastSessionSummary.topCornerActions.isEmpty()) {
                        "Corner coaching actions: unavailable"
                    } else {
                        state.lastSessionSummary.topCornerActions.joinToString(separator = "\n")
                    }
                    bottomBinding.lastSessionStrongestCorner.text = "Strongest corner: ${state.lastSessionSummary.strongestCorner}"
                    bottomBinding.lastSessionCornerOpportunity.text =
                        "Biggest corner opportunity: ${state.lastSessionSummary.biggestCornerOpportunity}"
                    bottomBinding.lastSessionActionLabel.text = if (state.lastSessionSummary.canOpenComparison) {
                        getString(R.string.open_compare_analysis)
                    } else {
                        getString(R.string.open_deep_analysis)
                    }
                    binding.invalidSessionBanner.visibility = if (state.invalidSessionMessage.isBlank()) View.GONE else View.VISIBLE
                    binding.invalidSessionBanner.text = state.invalidSessionMessage
                    updateComparisonSelection(state.compareSelection)
                    trackTileAdapter.submit(state.availableTracks, state.selectedTrackName)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _bottomBinding = null
        _binding = null
    }

    private fun setupTrackGrid() {
        trackTileAdapter = TrackTileAdapter { selectedTrackName ->
            sessionViewModel.selectTrack(selectedTrackName)
        }
        binding.trackRecycler.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.trackRecycler.adapter = trackTileAdapter
    }


    private fun openComparisonTools() {
        findNavController().navigate(R.id.action_mainFragment_to_comparisonFragment)
    }

    private fun setupComparisonSelectors() {
        bottomBinding.sessionASpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (parent?.adapter?.getItem(position) as? SessionSpinnerItem)?.let { session ->
                    sessionViewModel.selectCompareSessionA(session.id)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        bottomBinding.sessionBSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (parent?.adapter?.getItem(position) as? SessionSpinnerItem)?.let { session ->
                    sessionViewModel.selectCompareSessionB(session.id)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        bottomBinding.lapASpinnerInline.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sessionViewModel.selectLapA(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        bottomBinding.lapBSpinnerInline.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sessionViewModel.selectLapB(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun updateComparisonSelection(selection: com.kartingtracker.ui.CompareSelectionUiState) {
        val sessionItems = selection.sessionOptions.map { SessionSpinnerItem(it.id, it.label) }
        bottomBinding.sessionASpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sessionItems).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        bottomBinding.sessionBSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sessionItems).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val lapAdapterA = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, selection.lapOptionsA.map { it.label }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val lapAdapterB = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, selection.lapOptionsB.map { it.label }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        bottomBinding.lapASpinnerInline.adapter = lapAdapterA
        bottomBinding.lapBSpinnerInline.adapter = lapAdapterB
        bottomBinding.detailsButton.isEnabled = selection.canOpenComparison
    }

    private fun showAddTrackDialog() {
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.track_dialog_padding_horizontal)
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.new_track_hint)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        val input = TextInputEditText(requireContext()).apply { setSingleLine() }
        inputLayout.addView(input)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_new_track)
            .setView(inputLayout)
            .setPositiveButton(R.string.save_track, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val normalizedName = sessionViewModel.normalizeTrackName(input.text?.toString().orEmpty())
                when {
                    normalizedName.isBlank() -> inputLayout.error = getString(R.string.track_name_required)
                    sessionViewModel.trackExists(normalizedName) -> inputLayout.error = getString(R.string.track_name_exists)
                    sessionViewModel.createTrack(normalizedName) == null -> inputLayout.error = getString(R.string.track_name_exists)
                    else -> {
                        inputLayout.error = null
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
        input.requestFocus()
    }

    private fun showTrackManagementDialog() {
        val trackName = sessionViewModel.uiState.value.selectedTrackName
        if (trackName.isBlank()) return

        val actions = arrayOf(
            getString(R.string.edit_track_details),
            getString(R.string.edit_track_layout),
            getString(R.string.delete_track)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.edit_track_for, trackName))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showEditTrackDialog()
                    1 -> findNavController().navigate(R.id.action_mainFragment_to_trackLayoutFragment)
                    2 -> confirmDeleteTrack()
                }
            }
            .show()
    }

    private fun showEditTrackDialog() {
        val currentTrack = sessionViewModel.uiState.value.selectedTrackName
        if (currentTrack.isBlank()) return
        val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val nameLayout = TextInputLayout(requireContext()).apply { hint = getString(R.string.track_name_label) }
        val nameInput = TextInputEditText(requireContext()).apply { setText(currentTrack); setSingleLine() }
        val locationLayout = TextInputLayout(requireContext()).apply { hint = getString(R.string.track_location_label) }
        val locationInput = TextInputEditText(requireContext()).apply { setSingleLine() }
        val lengthLayout = TextInputLayout(requireContext()).apply { hint = getString(R.string.track_length_label) }
        val lengthInput = TextInputEditText(requireContext()).apply { setSingleLine() }
        nameLayout.addView(nameInput)
        locationLayout.addView(locationInput)
        lengthLayout.addView(lengthInput)
        container.addView(nameLayout)
        container.addView(locationLayout)
        container.addView(lengthLayout)

        sessionViewModel.loadTrack(currentTrack)?.let { track ->
            locationInput.setText(track.location.orEmpty())
            lengthInput.setText(track.lengthMeters?.toString().orEmpty())
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_track)
            .setView(container)
            .setPositiveButton(R.string.save_track) { _, _ ->
                val newName = sessionViewModel.normalizeTrackName(nameInput.text?.toString().orEmpty())
                if (newName.isNotBlank() && !newName.equals(currentTrack, ignoreCase = true)) {
                    sessionViewModel.renameTrack(currentTrack, newName)
                    sessionViewModel.selectTrack(newName)
                }
                val length = lengthInput.text?.toString()?.toFloatOrNull()
                val updatedTrack = sessionViewModel.loadTrack(newName.ifBlank { currentTrack })?.copy(
                    location = locationInput.text?.toString()?.trim().orEmpty().ifBlank { null },
                    lengthMeters = length
                )
                if (updatedTrack != null) {
                    sessionViewModel.updateTrack(updatedTrack)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteTrack() {
        val trackName = sessionViewModel.uiState.value.selectedTrackName
        if (trackName.isBlank()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_track)
            .setMessage(getString(R.string.delete_track_confirmation, trackName))
            .setPositiveButton(R.string.delete_track) { _, _ -> sessionViewModel.deleteTrack(trackName) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDetailsNavigationDialog() {
        val state = sessionViewModel.uiState.value
        val actions = mutableListOf(
            getString(R.string.open_last_session),
            getString(R.string.open_session_list),
            getString(R.string.open_lap_analysis),
            getString(R.string.open_coaching_insights),
            getString(R.string.open_time_loss_analysis),
            getString(R.string.open_sector_analysis),
            getString(R.string.open_track_learnings)
        )
        if (state.lastSessionSummary.canOpenComparison) {
            actions.add(2, getString(R.string.compare_laps))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tap_for_details)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.open_last_session) -> {
                        if (sessionViewModel.loadLastSession()) {
                            findNavController().navigate(R.id.action_mainFragment_to_lapsFragment)
                        }
                    }
                    getString(R.string.open_session_list) -> findNavController().navigate(R.id.action_mainFragment_to_sessionListFragment)
                    getString(R.string.compare_laps),
                    getString(R.string.open_lap_analysis),
                    getString(R.string.open_sector_analysis) -> {
                        sessionViewModel.setAnalysisMode(com.kartingtracker.ui.AnalysisMode.COMPARISON)
                        openComparisonTools()
                    }
                    getString(R.string.open_coaching_insights) -> {
                        sessionViewModel.setAnalysisMode(com.kartingtracker.ui.AnalysisMode.COACHING)
                        openComparisonTools()
                    }
                    getString(R.string.open_time_loss_analysis) -> {
                        sessionViewModel.setAnalysisMode(com.kartingtracker.ui.AnalysisMode.TIME_LOSS)
                        openComparisonTools()
                    }
                    getString(R.string.open_track_learnings) -> findNavController().navigate(R.id.action_mainFragment_to_trackLayoutFragment)
                }
            }
            .show()
    }
}

private data class SessionSpinnerItem(val id: Long, val label: String) {
    override fun toString(): String = label
}
