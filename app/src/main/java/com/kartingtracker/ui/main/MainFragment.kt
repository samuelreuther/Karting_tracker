package com.kartingtracker.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

        binding.startButton.setOnClickListener {
            sessionViewModel.startRecording()
        }
        binding.stopButton.setOnClickListener {
            sessionViewModel.stopRecording()
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

        binding.trackSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressTrackCallbacks) {
                    return
                }
                val selectedLabel = parent?.getItemAtPosition(position) as? String ?: return
                if (selectedLabel == SessionViewModel.CREATE_TRACK_OPTION) {
                    showCreateTrackDialog()
                } else {
                    sessionViewModel.selectTrack(selectedLabel)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionViewModel.uiState.collect { state ->
                    binding.recordingIndicator.isSelected = state.isRecording || state.isCalibrating
                    binding.recordingIndicator.text = state.statusLabel
                    binding.startButton.isEnabled = !state.isRecording && !state.isCalibrating && state.hasRequiredSensors
                    binding.stopButton.isEnabled = state.isRecording || state.isCalibrating
                    binding.trackSpinner.isEnabled = !state.isRecording && !state.isCalibrating
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
                    updateTrackSpinner(state.trackOptions, state.selectedTrackName)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateTrackSpinner(trackOptions: List<String>, selectedTrackName: String) {
        val options = trackOptions + SessionViewModel.CREATE_TRACK_OPTION
        suppressTrackCallbacks = true
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.trackSpinner.adapter = adapter
        val selectedIndex = trackOptions.indexOf(selectedTrackName).coerceAtLeast(0)
        binding.trackSpinner.setSelection(selectedIndex, false)
        suppressTrackCallbacks = false
    }

    private fun showCreateTrackDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.new_track_hint)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_new_track)
            .setView(input)
            .setPositiveButton(R.string.save_track) { _, _ ->
                val createdTrack = sessionViewModel.createTrack(input.text.toString())
                if (createdTrack == null) {
                    updateTrackSpinner(sessionViewModel.uiState.value.trackOptions, sessionViewModel.uiState.value.selectedTrackName)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                updateTrackSpinner(sessionViewModel.uiState.value.trackOptions, sessionViewModel.uiState.value.selectedTrackName)
            }
            .show()
    }
}
