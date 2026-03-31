package com.kartingtracker.ui.tracklayout

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.kartingtracker.R
import com.kartingtracker.data.TrackCorner
import com.kartingtracker.data.TrackDirection
import com.kartingtracker.data.TrackLayout
import com.kartingtracker.data.TrackPoint
import com.kartingtracker.databinding.FragmentTrackLayoutBinding
import com.kartingtracker.domain.TrackLayoutMapper
import com.kartingtracker.ui.AppViewModelFactory
import com.kartingtracker.ui.SessionViewModel
import com.kartingtracker.ui.TrackMapUiState
import kotlinx.coroutines.launch

class TrackLayoutFragment : Fragment() {
    private var _binding: FragmentTrackLayoutBinding? = null
    private val binding get() = _binding!!

    private val sessionViewModel: SessionViewModel by activityViewModels {
        AppViewModelFactory(requireActivity().application)
    }

    private var workingLayout: TrackLayout? = null
    private var trackMapUiState: TrackMapUiState = TrackMapUiState()
    private var editorMode: EditorMode = EditorMode.START_POINT

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { imageUri ->
        if (imageUri == null) {
            return@registerForActivityResult
        }

        val trackName = workingLayout?.trackName ?: sessionViewModel.uiState.value.selectedTrackName
        if (trackName.isBlank()) {
            return@registerForActivityResult
        }

        val importedLayout = sessionViewModel.importTrackLayoutImage(trackName, imageUri)
        if (importedLayout == null) {
            Toast.makeText(requireContext(), R.string.track_layout_image_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        val localLayout = workingLayout ?: importedLayout
        workingLayout = localLayout.copy(imagePath = importedLayout.imagePath)
        renderLayout()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrackLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val trackName = sessionViewModel.uiState.value.selectedTrackName
        if (trackName.isBlank()) {
            Toast.makeText(requireContext(), R.string.track_layout_track_required, Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }

        workingLayout = sessionViewModel.loadOrCreateTrackLayout(trackName)
        if (workingLayout == null) {
            Toast.makeText(requireContext(), R.string.track_layout_load_failed, Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }

        binding.selectImageButton.setOnClickListener {
            imagePicker.launch("image/*")
        }
        binding.undoCornerButton.setOnClickListener {
            updateWorkingLayout { layout ->
                layout.copy(corners = layout.corners.dropLast(1))
            }
        }
        binding.clearCornersButton.setOnClickListener {
            updateWorkingLayout { layout ->
                layout.copy(corners = emptyList())
            }
        }
        binding.saveLayoutButton.setOnClickListener {
            val layout = workingLayout ?: return@setOnClickListener
            sessionViewModel.saveTrackLayout(normalizeLayout(layout))
            Toast.makeText(requireContext(), R.string.track_layout_saved, Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }

        binding.editModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            editorMode = if (checkedId == R.id.addCornerButton) {
                EditorMode.ADD_CORNER
            } else {
                EditorMode.START_POINT
            }
            renderModeSummary()
        }
        binding.directionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            updateWorkingLayout { layout ->
                layout.copy(
                    direction = if (checkedId == R.id.counterClockwiseButton) {
                        TrackDirection.COUNTER_CLOCKWISE
                    } else {
                        TrackDirection.CLOCKWISE
                    }
                )
            }
        }
        binding.layoutEditorView.onTrackTap = { point ->
            when (editorMode) {
                EditorMode.START_POINT -> {
                    updateWorkingLayout { layout -> layout.copy(startPoint = point) }
                }

                EditorMode.ADD_CORNER -> {
                    updateWorkingLayout { layout ->
                        layout.copy(
                            corners = layout.corners + TrackCorner(
                                name = "Kurve ${layout.corners.size + 1}",
                                point = point
                            )
                        )
                    }
                }
            }
        }

        binding.startPointButton.isChecked = true
        renderLayout()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionViewModel.trackMapUiState.collect { state ->
                    trackMapUiState = state
                    renderLayout()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateWorkingLayout(transform: (TrackLayout) -> TrackLayout) {
        val updatedLayout = workingLayout?.let(transform) ?: return
        workingLayout = normalizeLayout(updatedLayout)
        renderLayout()
    }

    private fun normalizeLayout(layout: TrackLayout): TrackLayout {
        return layout.copy(corners = TrackLayoutMapper.sortAndRenameCorners(layout))
    }

    private fun renderLayout() {
        val layout = normalizeLayout(workingLayout ?: return)
        workingLayout = layout
        val trackMarkers = if (layout.imagePath.isBlank()) {
            emptyList()
        } else {
            TrackLayoutMapper.createTrackMarkers(layout, trackMapUiState.detectedCorners)
        }
        binding.trackNameValue.text = layout.trackName
        binding.layoutEditorView.setTrackImage(layout.imagePath)
        binding.layoutEditorView.renderLayout(layout)
        binding.layoutEditorView.renderMarkers(
            markers = trackMarkers,
            highlightedLabels = trackMapUiState.highlightedMarkerLabels
        )
        binding.clockwiseButton.isChecked = layout.direction == TrackDirection.CLOCKWISE
        binding.counterClockwiseButton.isChecked = layout.direction == TrackDirection.COUNTER_CLOCKWISE
        binding.imageStatusLabel.text = if (layout.imagePath.isBlank()) {
            getString(R.string.track_layout_no_image)
        } else {
            getString(R.string.track_layout_image_ready)
        }
        binding.startPointValue.text = formatPoint(layout.startPoint)
        binding.cornerCountValue.text = resources.getQuantityString(
            R.plurals.track_layout_corner_count,
            layout.corners.size,
            layout.corners.size
        )
        binding.cornerListValue.text = if (layout.corners.isEmpty()) {
            getString(R.string.track_layout_no_corners)
        } else {
            layout.corners.joinToString(separator = "\n") { corner -> corner.name }
        }
        binding.detectedCornerCountValue.text = resources.getQuantityString(
            R.plurals.track_layout_detected_corner_count,
            trackMapUiState.detectedCorners.size,
            trackMapUiState.detectedCorners.size
        )
        binding.detectedCornerListValue.text = when {
            trackMapUiState.detectedCorners.isEmpty() -> getString(R.string.track_layout_no_detected_corners)
            trackMarkers.isEmpty() -> trackMapUiState.fallbackCornerLines.joinToString(separator = "\n")
            else -> trackMapUiState.detectedCorners.mapIndexed { index, corner ->
                "K${index + 1} (~${corner.peakPercent.toInt()}%)"
            }.joinToString(separator = "\n")
        }
        binding.undoCornerButton.isEnabled = layout.corners.isNotEmpty()
        binding.clearCornersButton.isEnabled = layout.corners.isNotEmpty()
        binding.saveLayoutButton.isEnabled = layout.imagePath.isNotBlank()
        renderModeSummary()
    }

    private fun renderModeSummary() {
        binding.modeSummaryLabel.text = if (editorMode == EditorMode.START_POINT) {
            getString(R.string.track_layout_mode_start)
        } else {
            getString(R.string.track_layout_mode_corner)
        }
    }

    private fun formatPoint(point: TrackPoint): String {
        return "x=${"%.3f".format(point.x)}, y=${"%.3f".format(point.y)}"
    }

    private enum class EditorMode {
        START_POINT,
        ADD_CORNER
    }
}
