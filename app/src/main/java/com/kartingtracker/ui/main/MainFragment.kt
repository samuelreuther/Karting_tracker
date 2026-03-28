package com.kartingtracker.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionViewModel.uiState.collect { state ->
                    binding.recordingIndicator.isSelected = state.isRecording
                    binding.recordingIndicator.text = state.statusLabel
                    binding.startButton.isEnabled = !state.isRecording && state.hasRequiredSensors
                    binding.stopButton.isEnabled = state.isRecording
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
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
