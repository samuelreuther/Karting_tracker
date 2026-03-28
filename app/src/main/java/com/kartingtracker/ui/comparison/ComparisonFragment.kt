package com.kartingtracker.ui.comparison

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.data.LineData
import com.kartingtracker.R
import com.kartingtracker.databinding.FragmentComparisonBinding
import com.kartingtracker.ui.AppViewModelFactory
import com.kartingtracker.ui.SessionViewModel
import com.kartingtracker.ui.common.ChartUtils
import kotlinx.coroutines.launch

class ComparisonFragment : Fragment() {
    private var _binding: FragmentComparisonBinding? = null
    private val binding get() = _binding!!

    private val sessionViewModel: SessionViewModel by activityViewModels {
        AppViewModelFactory(requireActivity().application)
    }

    private var suppressSelectionCallbacks = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentComparisonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ChartUtils.configureLineChart(binding.longitudinalChart)
        ChartUtils.configureLineChart(binding.lateralChart)
        ChartUtils.configureLineChart(binding.deltaChart)

        binding.lapASpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressSelectionCallbacks) {
                    sessionViewModel.selectLapA(position)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.lapBSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressSelectionCallbacks) {
                    sessionViewModel.selectLapB(position)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionViewModel.comparisonUiState.collect { state ->
                    binding.emptyComparisonLabel.visibility =
                        if (state.lapLabels.isEmpty()) View.VISIBLE else View.GONE
                    binding.comparisonContent.visibility =
                        if (state.lapLabels.isEmpty()) View.GONE else View.VISIBLE
                    binding.summaryLabel.text = state.summaryLabel
                    binding.lapATimeLabel.text = state.lapATimeLabel
                    binding.lapBTimeLabel.text = state.lapBTimeLabel
                    binding.insightsLabel.text = state.insights.joinToString(separator = "\n")

                    if (state.lapLabels.isNotEmpty()) {
                        updateSpinner(binding.lapASpinner, state.lapLabels, state.selectedLapAIndex)
                        updateSpinner(binding.lapBSpinner, state.lapLabels, state.selectedLapBIndex)
                        binding.longitudinalChart.data = LineData(
                            ChartUtils.createDataSet(requireContext(), "Lap A", state.longitudinalLapA, R.color.karting_green),
                            ChartUtils.createDataSet(requireContext(), "Lap B", state.longitudinalLapB, R.color.karting_red),
                            ChartUtils.createMarkerDataSet(requireContext(), "A braking", state.longitudinalBrakeMarkersA, R.color.karting_red),
                            ChartUtils.createMarkerDataSet(requireContext(), "B braking", state.longitudinalBrakeMarkersB, R.color.karting_orange)
                        )
                        binding.lateralChart.data = LineData(
                            ChartUtils.createDataSet(requireContext(), "Lap A", state.lateralLapA, R.color.karting_blue),
                            ChartUtils.createDataSet(requireContext(), "Lap B", state.lateralLapB, R.color.karting_teal),
                            ChartUtils.createMarkerDataSet(requireContext(), "A cornering", state.lateralCornerMarkersA, R.color.karting_blue),
                            ChartUtils.createMarkerDataSet(requireContext(), "B cornering", state.lateralCornerMarkersB, R.color.karting_teal)
                        )
                        binding.deltaChart.data = LineData(
                            ChartUtils.createDataSet(requireContext(), "Longitudinal delta", state.deltaLongitudinal, R.color.karting_green),
                            ChartUtils.createDataSet(requireContext(), "Lateral delta", state.deltaLateral, R.color.karting_blue)
                        )
                        binding.longitudinalChart.invalidate()
                        binding.lateralChart.invalidate()
                        binding.deltaChart.invalidate()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateSpinner(spinner: android.widget.Spinner, labels: List<String>, selectedIndex: Int) {
        suppressSelectionCallbacks = true
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        spinner.setSelection(selectedIndex, false)
        suppressSelectionCallbacks = false
    }
}
