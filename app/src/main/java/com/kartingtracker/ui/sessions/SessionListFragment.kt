package com.kartingtracker.ui.sessions

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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kartingtracker.R
import com.kartingtracker.data.Session
import com.kartingtracker.databinding.FragmentSessionListBinding
import com.kartingtracker.ui.AppViewModelFactory
import com.kartingtracker.ui.SessionListItemUiState
import com.kartingtracker.ui.SessionViewModel
import kotlinx.coroutines.launch

class SessionListFragment : Fragment() {
    private var _binding: FragmentSessionListBinding? = null
    private val binding get() = _binding!!

    private val sessionViewModel: SessionViewModel by activityViewModels {
        AppViewModelFactory(requireActivity().application)
    }

    private val adapter = SessionListAdapter(::showOpenOptions, ::showDeleteOptions)
    private var suppressFilterCallbacks = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.sessionRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.sessionRecyclerView.adapter = adapter

        binding.trackFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressFilterCallbacks) {
                    val label = parent?.getItemAtPosition(position) as? String ?: return
                    sessionViewModel.selectSessionFilter(label)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionViewModel.sessionListUiState.collect { state ->
                    updateFilterSpinner(state.filterOptions, state.selectedFilter)
                    adapter.submitList(state.sessions)
                    binding.emptyStateLabel.visibility = if (state.sessions.isEmpty()) View.VISIBLE else View.GONE
                    binding.sessionRecyclerView.visibility = if (state.sessions.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateFilterSpinner(options: List<String>, selectedFilter: String) {
        suppressFilterCallbacks = true
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.trackFilterSpinner.adapter = adapter
        val selectedIndex = options.indexOf(selectedFilter).coerceAtLeast(0)
        binding.trackFilterSpinner.setSelection(selectedIndex, false)
        suppressFilterCallbacks = false
    }

    private fun showOpenOptions(item: SessionListItemUiState) {
        val session = item.session
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(session.trackName)
            .setItems(
                arrayOf(
                    getString(R.string.open_laps),
                    getString(R.string.open_comparison),
                    getString(R.string.reprocess_session)
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        sessionViewModel.loadSession(session)
                        findNavController().navigate(R.id.action_sessionListFragment_to_lapsFragment)
                    }

                    1 -> {
                        sessionViewModel.loadSession(session)
                        findNavController().navigate(R.id.action_sessionListFragment_to_comparisonFragment)
                    }

                    2 -> {
                        sessionViewModel.reprocessSession(session)
                    }
                }
            }
            .show()
    }

    private fun showDeleteOptions(item: SessionListItemUiState) {
        val session = item.session
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(session.trackName)
            .setItems(
                arrayOf(
                    getString(R.string.delete_session),
                    getString(R.string.delete_track)
                )
            ) { _, which ->
                when (which) {
                    0 -> confirmDeleteSession(session)
                    1 -> confirmDeleteTrack(session.trackName)
                }
            }
            .show()
    }

    private fun confirmDeleteSession(session: Session) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_session)
            .setMessage(getString(R.string.delete_session_confirmation, session.trackName))
            .setPositiveButton(R.string.delete_session) { _, _ ->
                sessionViewModel.deleteSession(session)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteTrack(trackName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_track)
            .setMessage(getString(R.string.delete_track_confirmation, trackName))
            .setPositiveButton(R.string.delete_track) { _, _ ->
                sessionViewModel.deleteTrack(trackName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
