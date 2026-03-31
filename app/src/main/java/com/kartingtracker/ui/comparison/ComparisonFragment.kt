package com.kartingtracker.ui.comparison

import android.graphics.Bitmap
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ComparisonFragment : Fragment() {
    private var _binding: FragmentComparisonBinding? = null
    private val binding get() = _binding!!

    private val sessionViewModel: SessionViewModel by activityViewModels {
        AppViewModelFactory(requireActivity().application)
    }

    private var suppressSelectionCallbacks = false
    private var mapBitmapJob: Job? = null
    private var currentMapPath: String? = null
    private var currentMapBitmap: Bitmap? = null

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
                    binding.idealLapLabel.text = state.idealLapLabel
                    binding.idealLapLabel.visibility = if (state.idealLapLabel.isBlank()) View.GONE else View.VISIBLE
                    binding.theoreticalBestLabel.text = state.theoreticalBestLabel
                    binding.theoreticalBestLabel.visibility = if (state.theoreticalBestLabel.isBlank()) View.GONE else View.VISIBLE
                    binding.idealLapSectorLabel.text = state.idealLapSectorLines.joinToString(separator = "\n")
                    binding.idealLapSectorLabel.visibility = if (state.idealLapSectorLines.isEmpty()) View.GONE else View.VISIBLE
                    binding.sectorComparisonLabel.text = state.sectorComparisonLines.joinToString(separator = "\n")
                    binding.sectorComparisonLabel.visibility = if (state.sectorComparisonLines.isEmpty()) View.GONE else View.VISIBLE
                    binding.insightsLabel.text = state.insights.joinToString(separator = "\n") { insight -> "- $insight" }
                    binding.insightsTitle.visibility = if (state.insights.isEmpty()) View.GONE else View.VISIBLE
                    binding.insightsLabel.visibility = if (state.insights.isEmpty()) View.GONE else View.VISIBLE
                    binding.sessionInsightsLabel.text = state.topTimeLossLines.joinToString(separator = "\n") { insight -> "- $insight" }
                    binding.sessionInsightsTitle.visibility = if (state.topTimeLossLines.isEmpty()) View.GONE else View.VISIBLE
                    binding.sessionInsightsLabel.visibility = if (state.topTimeLossLines.isEmpty()) View.GONE else View.VISIBLE
                    binding.trackMapTitle.visibility =
                        if (state.mapImagePath.isNullOrBlank() && state.fallbackCurveLines.isEmpty()) View.GONE else View.VISIBLE
                    binding.trackMapTitle.text = if (state.mapImagePath.isNullOrBlank()) {
                        "Detected Turns"
                    } else {
                        "Track Map"
                    }
                    binding.trackMapCard.visibility = if (state.mapImagePath.isNullOrBlank()) View.GONE else View.VISIBLE
                    binding.fallbackCurveLabel.text = state.fallbackCurveLines.joinToString(separator = "\n")
                    binding.fallbackCurveLabel.visibility = if (
                        state.fallbackCurveLines.isEmpty() ||
                        (!state.mapImagePath.isNullOrBlank() && state.projectedCurves.isNotEmpty())
                    ) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                    renderTrackMap(state)

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
                            ChartUtils.createDataSet(requireContext(), "Time Loss (A vs B)", state.timeLossEntries, R.color.karting_orange),
                            ChartUtils.createMarkerDataSet(requireContext(), "Time loss markers", state.segmentMarkerEntries, R.color.karting_red)
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
        mapBitmapJob?.cancel()
        currentMapBitmap = null
        currentMapPath = null
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

    private fun renderTrackMap(state: com.kartingtracker.ui.ComparisonUiState) {
        if (state.mapImagePath.isNullOrBlank()) {
            currentMapBitmap = null
            currentMapPath = null
            binding.trackMapOverlayView.render(bitmap = null, curves = emptyList())
            return
        }

        if (currentMapPath == state.mapImagePath && currentMapBitmap != null) {
            binding.trackMapOverlayView.render(bitmap = currentMapBitmap, curves = state.projectedCurves)
            return
        }

        mapBitmapJob?.cancel()
        currentMapPath = state.mapImagePath
        val mapPath = state.mapImagePath
        val projectedCurves = state.projectedCurves
        mapBitmapJob = viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = TrackMapBitmapStore.load(mapPath)
            if (_binding == null || currentMapPath != mapPath) {
                return@launch
            }
            currentMapBitmap = bitmap
            binding.trackMapOverlayView.render(bitmap = bitmap, curves = projectedCurves)
        }
    }
}
