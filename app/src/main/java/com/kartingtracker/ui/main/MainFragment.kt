package com.kartingtracker.ui.main

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.kartingtracker.R
import com.kartingtracker.databinding.FragmentMainBinding
import com.kartingtracker.ui.AppViewModelFactory
import com.kartingtracker.ui.SessionViewModel
import com.kartingtracker.ui.common.formatLapTime
import kotlinx.coroutines.launch

class MainFragment : Fragment() {
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val sessionViewModel: SessionViewModel by activityViewModels {
        AppViewModelFactory(requireActivity().application)
    }

    private var suppressTrackCallbacks = false
    private var currentDropdownOptions: List<String> = emptyList()
    private var trackDropdownAdapter: TrackDropdownAdapter? = null

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

        setupTrackDropdown()

        binding.startButton.setOnClickListener {
            sessionViewModel.startRecording()
        }
        binding.stopButton.setOnClickListener {
            sessionViewModel.stopRecording()
        }
        binding.editTrackButton.setOnClickListener {
            showEditTrackDialog()
        }
        binding.deleteTrackButton.setOnClickListener {
            confirmDeleteTrack()
        }
        binding.editTrackLayoutButton.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_trackLayoutFragment)
        }
        binding.viewLapsButton.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_lapsFragment)
        }
        binding.compareLapsButton.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_comparisonFragment)
        }
        binding.browseSessionsButton.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_sessionListFragment)
        }
        binding.loadLastSessionButton.setOnClickListener {
            val loaded = sessionViewModel.loadLastSession()
            if (loaded) {
                findNavController().navigate(R.id.action_mainFragment_to_lapsFragment)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionViewModel.uiState.collect { state ->
                    binding.recordingIndicator.isSelected = state.isRecording || state.isCalibrating
                    binding.recordingIndicator.text = state.statusLabel
                    binding.startButton.isEnabled = !state.isRecording &&
                        !state.isCalibrating &&
                        state.hasRequiredSensors &&
                        state.hasValidSelectedTrack
                    binding.stopButton.isEnabled = state.isRecording || state.isCalibrating
                    binding.trackDropdown.isEnabled = !state.isRecording && !state.isCalibrating
                    binding.editTrackButton.isEnabled = !state.isRecording && !state.isCalibrating && state.hasValidSelectedTrack
                    binding.deleteTrackButton.isEnabled = !state.isRecording && !state.isCalibrating && state.hasValidSelectedTrack
                    binding.editTrackLayoutButton.isEnabled = !state.isRecording &&
                        !state.isCalibrating &&
                        state.hasValidSelectedTrack
                    binding.sensorAvailabilityLabel.visibility = if (state.hasRequiredSensors) View.GONE else View.VISIBLE
                    binding.samplesValue.text = state.sampleCount.toString()
                    binding.longitudinalValue.text = getString(
                        R.string.accel_value_format,
                        state.liveLongitudinalAccel
                    )
                    binding.lateralValue.text = getString(
                        R.string.accel_value_format,
                        state.liveLateralAccel
                    )
                    binding.detectedLapsValue.text = state.lapCount.toString()
                    binding.estimatedLapValue.text = state.estimatedLapTimeMs?.let(::formatLapTime) ?: "n/a"
                    binding.trackProfileLabel.text = state.trackProfileSummary
                    binding.trackProfileLabel.visibility = View.VISIBLE
                    binding.loadLastSessionButton.isEnabled = state.canLoadLastSession
                    updateTrackDropdown(state.trackOptions, state.selectedTrackName)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        trackDropdownAdapter = null
        _binding = null
    }

    private fun setupTrackDropdown() {
        binding.trackDropdown.keyListener = null
        binding.trackDropdown.setOnClickListener {
            showTrackDropdown()
        }
        binding.trackDropdown.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                showTrackDropdown()
            }
            false
        }
        binding.trackDropdown.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.trackDropdown.post(::showTrackDropdown)
            }
        }
        binding.trackDropdown.setOnDismissListener {
            binding.trackDropdown.clearFocus()
        }
        binding.trackDropdown.setOnItemClickListener { parent, _, position, _ ->
            if (suppressTrackCallbacks) {
                return@setOnItemClickListener
            }

            val selectedLabel = parent?.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
            handleTrackSelection(selectedLabel)
        }
    }

    private fun updateTrackDropdown(trackOptions: List<String>, selectedTrackName: String) {
        val options = trackOptions + getString(R.string.add_new_track_option)
        if (trackDropdownAdapter == null) {
            trackDropdownAdapter = TrackDropdownAdapter(requireContext(), options)
            binding.trackDropdown.setAdapter(trackDropdownAdapter)
            currentDropdownOptions = options
        } else if (currentDropdownOptions != options) {
            currentDropdownOptions = options
            trackDropdownAdapter?.replaceItems(options)
        }

        suppressTrackCallbacks = true
        val displayText = selectedTrackName.takeIf { it.isNotBlank() }.orEmpty()
        if (binding.trackDropdown.text.toString() != displayText) {
            binding.trackDropdown.setText(displayText, false)
        }
        suppressTrackCallbacks = false
    }

    private fun handleTrackSelection(selectedLabel: String) {
        if (selectedLabel == getString(R.string.add_new_track_option)) {
            binding.trackDropdown.setText(sessionViewModel.uiState.value.selectedTrackName, false)
            showAddTrackDialog()
            return
        }

        sessionViewModel.selectTrack(selectedLabel)
        binding.trackDropdown.dismissDropDown()
        binding.trackDropdown.clearFocus()
    }

    private fun showTrackDropdown() {
        if (!binding.trackDropdown.isEnabled) {
            return
        }
        binding.trackDropdown.requestFocus()
        binding.trackDropdown.post { binding.trackDropdown.showDropDown() }
    }

    private fun showAddTrackDialog() {
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.track_dialog_padding_horizontal)
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.new_track_hint)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        val input = TextInputEditText(requireContext()).apply {
            setSingleLine()
        }
        inputLayout.addView(input)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_new_track)
            .setView(inputLayout)
            .setPositiveButton(R.string.save_track, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                updateTrackDropdown(
                    sessionViewModel.uiState.value.trackOptions,
                    sessionViewModel.uiState.value.selectedTrackName
                )
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val normalizedName = sessionViewModel.normalizeTrackName(input.text?.toString().orEmpty())
                when {
                    normalizedName.isBlank() -> {
                        inputLayout.error = getString(R.string.track_name_required)
                    }

                    sessionViewModel.trackExists(normalizedName) -> {
                        inputLayout.error = getString(R.string.track_name_exists)
                    }

                    sessionViewModel.createTrack(normalizedName) == null -> {
                        inputLayout.error = getString(R.string.track_name_exists)
                    }

                    else -> {
                        inputLayout.error = null
                        dialog.dismiss()
                    }
                }
            }
        }

        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                inputLayout.error = null
            }
        }

        dialog.show()
        input.requestFocus()
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

        val existing = sessionViewModel.uiState.value.trackOptions.firstOrNull { it.equals(currentTrack, ignoreCase = true) }
        if (existing != null) {
            sessionViewModel.loadTrack(existing)?.let { track ->
                locationInput.setText(track.location.orEmpty())
                lengthInput.setText(track.lengthMeters?.toString().orEmpty())
            }
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
            .setPositiveButton(R.string.delete_track) { _, _ ->
                sessionViewModel.deleteTrack(trackName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class TrackDropdownAdapter(
        context: Context,
        items: List<String>
    ) : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items.toMutableList()) {
        private val allItems = items.toMutableList()
        private val noFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): Filter.FilterResults {
                return Filter.FilterResults().apply {
                    values = allItems.toList()
                    count = allItems.size
                }
            }

            override fun publishResults(constraint: CharSequence?, results: Filter.FilterResults?) {
                clear()
                addAll(allItems)
                notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?): CharSequence {
                return resultValue as? CharSequence ?: super.convertResultToString(resultValue)
            }
        }

        fun replaceItems(items: List<String>) {
            allItems.clear()
            allItems.addAll(items)
            clear()
            addAll(allItems)
            notifyDataSetChanged()
        }

        override fun getFilter(): Filter = noFilter
    }
}
