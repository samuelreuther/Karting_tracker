package com.kartingtracker.ui.main

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.kartingtracker.R
import com.kartingtracker.databinding.FragmentMainBinding
import com.kartingtracker.ui.AppViewModelFactory
import com.kartingtracker.ui.SessionViewModel
import kotlinx.coroutines.launch

class MainFragment : Fragment() {
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

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
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTrackGrid()

        binding.startButton.setOnClickListener { sessionViewModel.startRecording() }
        binding.stopButton.setOnClickListener { sessionViewModel.stopRecording() }
        binding.addTrackButton.setOnClickListener { showAddTrackDialog() }
        binding.detailsButton.setOnClickListener { showDetailsNavigationDialog() }
        binding.editTrackButton.setOnClickListener { showTrackManagementDialog() }
        binding.lastSessionCard.setOnClickListener {
            showDetailsNavigationDialog()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionViewModel.uiState.collect { state ->
                    binding.recordingIndicator.isSelected = state.isRecording || state.isCalibrating || state.isPreparing || state.isStopping
                    binding.recordingIndicator.text = state.statusLabel
                    binding.recordingTimerLabel.text = state.recordingTimerLabel
                    binding.startButton.isEnabled = !state.isRecording &&
                        !state.isPreparing &&
                        !state.isCalibrating &&
                        !state.isStopping &&
                        state.hasRequiredSensors &&
                        state.hasValidSelectedTrack
                    binding.stopButton.isEnabled = state.isRecording || state.isCalibrating || state.isPreparing || state.isStopping
                    binding.editTrackButton.isEnabled = !state.isRecording && !state.isCalibrating && !state.isPreparing && !state.isStopping && state.hasValidSelectedTrack
                    binding.sensorAvailabilityLabel.visibility = if (state.hasRequiredSensors) View.GONE else View.VISIBLE
                    binding.trackProfileLabel.text = state.trackProfileSummary
                    binding.detailsButton.isEnabled = true
                    binding.heroTrackValue.text = state.selectedTrackName.ifBlank {
                        getString(R.string.no_track_selected)
                    }
                    binding.heroRecommendation.text = state.lastSessionSummary.headline
                    binding.lastSessionHeadline.text = state.lastSessionSummary.headline
                    binding.lastSessionQuality.text = state.lastSessionSummary.quality
                    binding.lastSessionTimeLoss.text = state.lastSessionSummary.biggestLoss
                    binding.lastSessionHint.text = state.lastSessionSummary.coachingHint
                    binding.lastSessionActionLabel.text = if (state.lastSessionSummary.canOpenComparison) {
                        getString(R.string.open_compare_analysis)
                    } else {
                        getString(R.string.open_deep_analysis)
                    }
                    trackTileAdapter.submit(state.availableTracks, state.selectedTrackName)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupTrackGrid() {
        trackTileAdapter = TrackTileAdapter { selectedTrackName ->
            sessionViewModel.selectTrack(selectedTrackName)
        }
        binding.trackRecycler.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.trackRecycler.adapter = trackTileAdapter
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
                    getString(R.string.open_last_session) -> sessionViewModel.loadLastSession()
                    getString(R.string.open_session_list) -> findNavController().navigate(R.id.action_mainFragment_to_sessionListFragment)
                    getString(R.string.compare_laps),
                    getString(R.string.open_lap_analysis),
                    getString(R.string.open_coaching_insights),
                    getString(R.string.open_time_loss_analysis),
                    getString(R.string.open_sector_analysis) -> findNavController().navigate(R.id.action_mainFragment_to_comparisonFragment)
                    getString(R.string.open_track_learnings) -> findNavController().navigate(R.id.action_mainFragment_to_trackLayoutFragment)
                }
            }
            .show()
    }
}
